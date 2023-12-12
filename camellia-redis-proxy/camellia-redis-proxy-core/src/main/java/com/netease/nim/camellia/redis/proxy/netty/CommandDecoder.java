package com.netease.nim.camellia.redis.proxy.netty;

import com.netease.nim.camellia.redis.proxy.command.Command;
import com.netease.nim.camellia.redis.proxy.conf.Constants;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.ByteProcessor;
import io.netty.util.CharsetUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 协议解码
 *
 * Created by caojiajun on 2021/9/22
 */
public class CommandDecoder extends ByteToMessageDecoder {

    private List<Command> commands;

    private byte[][] bytes;
    private int index = 0;

    private int commandDecodeMaxBatchSize = Constants.Server.commandDecodeMaxBatchSize;
    private int commandDecodeBufferInitializerSize = Constants.Server.commandDecodeBufferInitializerSize;

    public CommandDecoder(int commandDecodeMaxBatchSize, int commandDecodeBufferInitializerSize) {
        super();
        if (commandDecodeMaxBatchSize > 0) {
            this.commandDecodeMaxBatchSize = commandDecodeMaxBatchSize;
        }
        if (commandDecodeBufferInitializerSize > 0) {
            this.commandDecodeBufferInitializerSize = commandDecodeBufferInitializerSize;
        }
        this.commands = new ArrayList<>(this.commandDecodeBufferInitializerSize);
    }

    /**
     * 根据RESP协议去解析 {@link ByteBuf}  *1\r\n$5\r\nhello\r\n
     * <p> Parse the ByteBuf object according RESP protocol.
     * <p> https://redis.io/docs/reference/protocol-spec/
     * @param ctx the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in  the {@link ByteBuf} from which to read data
     * @param out the {@link List} to which decoded messages should be added
     */
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        try {
            while (true) {
                if (bytes == null) {
                    if (in.readableBytes() <= 0) {
                        return;
                    }
                    //获取当前读索引
                    int readerIndex = in.readerIndex();
                    //从当前读索引读取一个字节
                    byte b = in.readByte();
                    if (b == '*') {
                        ByteBuf byteBuf = readLine(in);
                        //当前行为null，重置当前读索引并返回
                        if (byteBuf == null) {
                            in.readerIndex(readerIndex);
                            return;
                        }
                        //获取RESP解析的数组长度
                        int number = (int)parseRedisNumber(byteBuf);
                        bytes = new byte[number][];
                    } else {
                        throw new IllegalArgumentException();
                    }
                } else {
                    int numArgs = bytes.length;
                    for (int i = index; i < numArgs; i++) {
                        if (in.readableBytes() <= 0) {
                            return;
                        }
                        int readerIndex = in.readerIndex();
                        //从当前读索引读取一个字节，如果是$表示为字符串
                        if (in.readByte() == '$') {
                            ByteBuf byteBuf = readLine(in);
                            if (byteBuf == null) {
                                in.readerIndex(readerIndex);
                                return;
                            }
                            //获取字符串长度
                            int size = (int)parseRedisNumber(byteBuf);
                            if (in.readableBytes() >= size + 2) {
                                //如果可读字节长度>=字符串长度+2,则读取对应字符串，保存到bytes
                                bytes[i] = new byte[size];
                                in.readBytes(bytes[i]);
                                in.skipBytes(2);
                            } else {
                                //如果可读字节长度<字符串长度+2，则重设读索引并返回
                                in.readerIndex(readerIndex);
                                return;
                            }
                            index = i+1;
                        } else {
                            throw new IllegalArgumentException("Unexpected character");
                        }
                    }
                    try {
                        //封装当前这条redis命令
                        Command command = new Command(bytes);
                        //将redis命令保存在集合
                        commands.add(command);
                        if (commands.size() >= commandDecodeMaxBatchSize) {
                            //如果命令条数超过最大批值，则先进行批量处理
                            out.add(commands);
                            commands = new ArrayList<>(commandDecodeBufferInitializerSize);
                        }
                    } finally {
                        //重置bytes，表示当前这条命令已经成功解析
                        bytes = null;
                        index = 0;
                    }
                }
            }
        } finally {
            //先处理解析好的命令
            if (!commands.isEmpty()) {
                out.add(commands);
                commands = new ArrayList<>(commandDecodeBufferInitializerSize);
            }
        }
    }

    private static final int POSITIVE_LONG_MAX_LENGTH = 19; // length of Long.MAX_VALUE
    private static final int EOL_LENGTH = 2;

    private final NumberProcessor numberProcessor = new NumberProcessor();

    /**
     * Get the number from byteBuf
     * @param byteBuf byteBuf
     * @return number
     */
    private long parseRedisNumber(ByteBuf byteBuf) {
        //首先，代码获取byteBuf的可读字节数，并检查是否大于0，并且第一个字节是否为负号（-）。
        //如果是，将negative标志设置为true，否则设置为false。
        final int readableBytes = byteBuf.readableBytes();
        final boolean negative = readableBytes > 0 && byteBuf.getByte(byteBuf.readerIndex()) == '-';
        final int extraOneByteForNegative = negative ? 1 : 0;
        //代码检查可读字节数是否小于等于额外的一个字节。如果是，则抛出异常，表示没有数字可以解析。
        if (readableBytes <= extraOneByteForNegative) {
            throw new IllegalArgumentException("no number to parse: " + byteBuf.toString(CharsetUtil.US_ASCII));
        }
        //代码检查可读字节数是否大于最大正整数的长度加上额外的一个字节。如果是，则抛出异常，表示字符数太多，无法解析为有效的 RESP 整数。
        if (readableBytes > POSITIVE_LONG_MAX_LENGTH + extraOneByteForNegative) {
            throw new IllegalArgumentException("too many characters to be a valid RESP Integer: " +
                    byteBuf.toString(CharsetUtil.US_ASCII));
        }
        if (negative) {
            numberProcessor.reset();
            byteBuf.skipBytes(extraOneByteForNegative);
            byteBuf.forEachByte(numberProcessor);
            return -1 * numberProcessor.content();
        }
        //重置numberProcessor，然后处理后返回
        numberProcessor.reset();
        byteBuf.forEachByte(numberProcessor);
        return numberProcessor.content();
    }

    /**
     * 把字节类型累加为数字
     */
    private static final class NumberProcessor implements ByteProcessor {
        private long result;
        @Override
        public boolean process(byte value) {
            if (value < '0' || value > '9') {
                throw new IllegalArgumentException("bad byte in number: " + value);
            }
            result = result * 10 + (value - '0');
            return true;
        }
        public long content() {
            return result;
        }
        public void reset() {
            result = 0;
        }
    }

    /**
     * 读取完整一行数据
     *
     * @param in
     * @return
     */
    private static ByteBuf readLine(ByteBuf in) {
        //检查是否可读取至少EOL_LENGTH个字节，如果不可读，表示没有数据可读
        if (!in.isReadable(EOL_LENGTH)) {
            return null;
        }
        //查找换行符位置，找不到直接返回（表示没有找到完整的一行数据）
        final int lfIndex = in.forEachByte(ByteProcessor.FIND_LF);
        if (lfIndex < 0) {
            return null;
        }
        //读取当前索引到换行符之前的数据
        ByteBuf data = in.readSlice(lfIndex - in.readerIndex() - 1); // `-1` is for CR
        in.skipBytes(2);
        return data;
    }
}

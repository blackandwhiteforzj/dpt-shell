package com.yeahka.dexshadow.util;

/**
 * Copyright © Yeahka All Rights Reserved.
 *
 * @author jimmyzhou
 * @date 2024/7/3
 * @desc todo
 */
public class Endian {
    /**
     * 是为了确保数据在小端序系统上能够正确存储和读取，特别是当你要生成或解析二进制文件时。
     * 如果你的代码要兼容不同的硬件平台或文件格式（如 DEX 文件），这种字节序的转换是必不可少的
     * @param number
     * @return
     */
    public static byte[] makeLittleEndian(int number){
        byte[] bytes = new byte[4];
        bytes[0] = (byte)(number & 0xff);
        bytes[1] = (byte)((number & 0xff00) >> 8);
        bytes[2] = (byte)((number & 0xff0000) >> 16);
        bytes[3] = (byte)((number & 0xff000000) >> 24);

        return bytes;
    }

    public static byte[] makeLittleEndian(short number){
        byte[] bytes = new byte[2];
        bytes[0] = (byte)(number & 0xff);
        bytes[1] = (byte)((number & 0xff00) >> 8);

        return bytes;
    }
}

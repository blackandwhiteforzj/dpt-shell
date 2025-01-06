package com.yeahka.dexshadow.util;

import java.util.Arrays;
import java.util.Locale;

/**
 * Copyright © Yeahka All Rights Reserved.
 *
 * @author jimmyzhou
 * @date 2024/7/3
 * @desc 16进制工具类
 */
public class HexUtils {

    public static String toHexArray(byte[] data) {
        String[] array = new String[data.length];
        for (int i = 0; i < data.length; i++) {
            int value = data[i];
            if (data[i] < 0) {
                value = data[i] + 256;
            }
            array[i] = String.format(Locale.US, "%02x", value);
        }
        return Arrays.toString(array);
    }
}

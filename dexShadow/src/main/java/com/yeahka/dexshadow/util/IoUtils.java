package com.yeahka.dexshadow.util;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Copyright © Yeahka All Rights Reserved.
 *
 * @author jimmyzhou
 * @date 2024/6/25
 * @desc Io读写工具类
 */
public class IoUtils {
    public static byte[] readFile(String file){
        FileInputStream fileInputStream = null;
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try {
            fileInputStream = new FileInputStream(file);
            int len = -1;
            byte[] buf = new byte[4096];
            while((len = fileInputStream.read(buf)) != -1){
                byteArrayOutputStream.write(buf,0,len);
            }
        }
        catch (Exception e){
            e.printStackTrace();
        }
        finally {
            close(fileInputStream);
            close(byteArrayOutputStream);
        }
        return byteArrayOutputStream.toByteArray();
    }

    public static void writeFile(String dest,byte[] data){
        writeFile(dest,data,false);
    }

    public static void appendFile(String dest,byte[] data){
        writeFile(dest,data,true);
    }

    public static void writeFile(String dest,byte[] data,boolean append){
        FileOutputStream fileOutputStream = null;
        try{
            fileOutputStream = new FileOutputStream(dest,append);
            fileOutputStream.write(data);
        }
        catch (IOException e){
            e.printStackTrace();
        }
        finally {
            close(fileOutputStream);
        }
    }

    public static void close(Closeable closeable){
        if(closeable != null){
            try {
                closeable.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

}

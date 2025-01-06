package com.yeahka.dexshadow.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedList;
import java.util.Queue;
import java.util.zip.Adler32;

/**
 * Copyright © Yeahka All Rights Reserved.
 *
 * @author jimmyzhou
 * @date 2024/6/25
 * @desc 文件处理工具类
 */
public class FileUtils {
    public static void copy(String src,String dest){
        File srcFile = new File(src);
        File destFile = new File(dest);
        Queue<File> queue = new LinkedList<>();
        queue.offer(srcFile);
        while(!queue.isEmpty()) {
            File origin = queue.poll();
            File target = null;
            if (origin.isDirectory()) {
                target = new File(destFile, origin.getName());
                File[] subOrigin = origin.listFiles();
                if(subOrigin != null) {
                    for (File f : subOrigin) {
                        queue.offer(f);
                    }
                }
                if (!target.exists()) {
                    target.mkdirs();
                }
            } else {

                target = new File(dest,origin.getParentFile().getName());
                File targetFile = new File(target,origin.getName());
                byte[] data = IoUtils.readFile(origin.getAbsolutePath());
                IoUtils.writeFile(targetFile.getAbsolutePath(),data);
            }
        }
    }

    public static String getNewFileName(String fileName,String tag){
        String fileSuffix = fileName.substring(fileName.lastIndexOf(".") + 1);
        return fileName.replaceAll("\\." + fileSuffix + "$","_" + tag + "." + fileSuffix) ;
    }

    public static String getNewFileSuffix(String fileName,String newSuffix){
        String fileSuffix = fileName.substring(fileName.lastIndexOf(".") + 1);
        return fileName.replaceAll("\\." + fileSuffix + "$",  "." + newSuffix) ;
    }

    /**
     * Get a directory and create it if it doesn't exist
     */
    public static File getDir(String path,String dirName){
        File dirFile = new File(path,dirName);
        if(!dirFile.exists()){
            dirFile.mkdirs();
        }
        return dirFile;
    }

    /**
     * Recursively delete directories or files
     */
    public static void deleteRecurse(File file){
        if(file.isDirectory()){
            File[] files = file.listFiles();
            if(files != null){
                for (File f : files) {
                    deleteRecurse(f);
                }
            }
        }
        file.delete();
    }

    /**
     * Gets the directory where the current command tool is located
     */
    public static String getExecutablePath(){
        return System.getProperty("user.dir");
    }

    /**
     * 修改dex头，CheckSum 校验码
     *
     * @param dexBytes
     */
    public static void fixCheckSumHeader(byte[] dexBytes) {
        Adler32 adler = new Adler32();
        adler.update(dexBytes, 12, dexBytes.length - 12);//从12到文件末尾计算校验码
        long value = adler.getValue();
        int va = (int) value;
        byte[] newcs = intToByte(va);
        //高位在前，低位在前掉个个
        byte[] recs = new byte[4];
        for (int i = 0; i < 4; i++) {
            recs[i] = newcs[newcs.length - 1 - i];
        }
        System.arraycopy(recs, 0, dexBytes, 8, 4);//效验码赋值（8-11）
    }


    /**
     * int 转byte[]
     *
     * @param number
     * @return
     */
    public static byte[] intToByte(int number) {
        byte[] b = new byte[4];
        for (int i = 3; i >= 0; i--) {
            b[i] = (byte) (number % 256);
            number >>= 8;
        }
        return b;
    }

    /**
     * 修改dex头 sha1值
     *
     * @param dexBytes
     * @throws NoSuchAlgorithmException
     */
    public static void fixSHA1Header(byte[] dexBytes)
            throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        md.update(dexBytes, 32, dexBytes.length - 32);//从32为到结束计算sha--1
        byte[] newdt = md.digest();
        System.arraycopy(newdt, 0, dexBytes, 12, 20);//修改sha-1值（12-31）
    }

    /**
     * 修改dex头 file_size值
     *
     * @param dexBytes
     */
    public static void fixFileSizeHeader(byte[] dexBytes) {
        //新文件长度
        byte[] newfs = intToByte(dexBytes.length);
        byte[] refs = new byte[4];
        //高位在前，低位在前掉个个
        for (int i = 0; i < 4; i++) {
            refs[i] = newfs[newfs.length - 1 - i];
        }
        System.arraycopy(refs, 0, dexBytes, 32, 4);//修改（32-35）
    }


}

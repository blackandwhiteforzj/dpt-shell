package com.yeahka.dexshadow.util;

import com.android.apksigner.ApkSignerTool;
import com.iyxan23.zipalignjava.ZipAlign;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Copyright © Yeahka All Rights Reserved.
 *
 * @author jimmyzhou
 * @date 2024/3/8
 * @desc todo
 */
public class SignUtils {
    public static boolean signApk(String apkPath, String keyStorePath, String signedApkPath,
                                  String storePassword
                                 ) {
        ArrayList<String> commandList = new ArrayList<>();

        commandList.add("sign");
        commandList.add("--ks");
        commandList.add(keyStorePath);
//        commandList.add("--ks-key-alias");
//        commandList.add(keyAlias);
        commandList.add("--ks-pass");
        commandList.add("pass:" + storePassword);
//        commandList.add("--key-pass");
//        commandList.add("pass:" + KeyPassword);
        commandList.add("--out");
        commandList.add(signedApkPath);
        commandList.add("--v1-signing-enabled");
        commandList.add("true");
        commandList.add("--v2-signing-enabled");
        commandList.add("true");
        commandList.add("--v3-signing-enabled");
        commandList.add("true");
        commandList.add(apkPath);

        int size = commandList.size();
        String[] commandArray = new String[size];
        commandArray = commandList.toArray(commandArray);

        try {
            ApkSignerTool.main(commandArray);

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public static void zipalignApk(String inputApkPath, String outputApkPath) {
        try {
            RandomAccessFile in = new RandomAccessFile(inputApkPath, "r");
            FileOutputStream out = new FileOutputStream(outputApkPath);
            ZipAlign.alignZip(in, out);
            IoUtils.close(in);
            IoUtils.close(out);
        } catch (Exception e) {

        }

    }

    public static void signApkWithZipalign(String out, File file, KeyStore store, File originFile, boolean isChannel,boolean isZipalign ) {
        LogUtils.info("normalSignApk");
        File signedFile;
        File zipalignFile;
        try {
            if (isChannel) {
                String[] s = file.getName().split("_");
                String market = s[s.length - 1].split("\\.")[0];
                LogUtils.info("应用市场=" + market);
                zipalignFile = new File(out, market + "_zipalign.apk");
                zipalignApk(file.getAbsolutePath(), zipalignFile.getAbsolutePath());
                if(isZipalign){
                    signedFile = new File(out, originFile.getName().substring(0, originFile.getName().length() - 4).replace("_unzipalign","") + "_YKJiaGu_" + market + "_Signed.apk");
                }else {
                    signedFile = new File(out, originFile.getName().substring(0, originFile.getName().length() - 4).replace("_unzipalign","") + "_" + market + "_Signed.apk");

                }
            } else {
                zipalignFile = new File(out, originFile.getName().substring(0, originFile.getName().length() - 4) + "_zipalign.apk");
                zipalignApk(file.getAbsolutePath(), zipalignFile.getAbsolutePath());
                signedFile = new File(out, originFile.getName().substring(0, originFile.getName().length() - 4).replace("_unzipalign","") + "_YKJiaGu_Signed.apk");
            }
            signApk(zipalignFile.getAbsolutePath(), store.oldStoreFile, signedFile.getAbsolutePath(), store.oldStorePassword);
            file.delete();
            new File(signedFile.getAbsolutePath() + ".idsig").delete();
            zipalignFile.delete();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    public static void realSign(boolean isRotate,String out, File file, KeyStore store, File originFile, boolean isChannel,boolean isZipalign){
        if(isRotate){
            rotateSignApkWithZipalign(out, file, store, originFile, isChannel,isZipalign);
        }else {
            signApkWithZipalign(out, file, store, originFile, isChannel,isZipalign);
        }
    }
    public static void rotateSignApkWithZipalign(String out, File file, KeyStore store, File originFile, boolean isChannel,boolean isZipalign){
        LogUtils.info("rotateSignApk");
        File signedFile;
        File zipalignFile;
        try {
            if (isChannel) {
                String[] s = file.getName().split("_");
                String market = s[s.length - 1].split("\\.")[0];
                LogUtils.info("应用市场=" + market);
                zipalignFile = new File(out, market + "_zipalign.apk");
                zipalignApk(file.getAbsolutePath(), zipalignFile.getAbsolutePath());
                if(isZipalign){
                    signedFile = new File(out, originFile.getName().substring(0, originFile.getName().length() - 4).replace("_unzipalign","") + "_YKJiaGu_" + market + "_Signed.apk");

                }else {
                    signedFile = new File(out, originFile.getName().substring(0, originFile.getName().length() - 4).replace("_unzipalign","") + "_" + market + "_Signed.apk");

                }
            } else {
                zipalignFile = new File(out, originFile.getName().substring(0, originFile.getName().length() - 4) + "_zipalign.apk");
                zipalignApk(file.getAbsolutePath(), zipalignFile.getAbsolutePath());
                signedFile = new File(out, originFile.getName().substring(0, originFile.getName().length() - 4).replace("_unzipalign","") + "_YKJiaGu_Signed.apk");
            }
            rotateSignApk(zipalignFile.getAbsolutePath(), store.oldStoreFile, store.newStoreFile, store.oldStorePassword, store.newStorePassword,signedFile.getAbsolutePath());
            file.delete();
            new File(signedFile.getAbsolutePath() + ".idsig").delete();
            zipalignFile.delete();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    public static boolean rotateSignApk(String apkPath, String oldKeyStorePath,String newKeyStorePath,
                                  String oldStorePassword,
                                  String newStorePassword,
                                        String signedApkPath
                                ) {
       String lineage = generateRotateCerts(oldKeyStorePath,newKeyStorePath,oldStorePassword,newStorePassword);
        ArrayList<String> commandList = new ArrayList<>();

        commandList.add("sign");
        commandList.add("--rotation-min-sdk-version");
        commandList.add("21");
        commandList.add("--ks");
        commandList.add(oldKeyStorePath);
        commandList.add("--ks-pass");
        commandList.add("pass:" + oldStorePassword);
        commandList.add("--next-signer");
        commandList.add("--ks");
        commandList.add(newKeyStorePath);
        commandList.add("--ks-pass");
        commandList.add("pass:" + newStorePassword);
        commandList.add("--lineage");
        commandList.add(lineage);
        commandList.add("--out");
        commandList.add(signedApkPath);
        commandList.add("--v1-signing-enabled");
        commandList.add("true");
        commandList.add("--v2-signing-enabled");
        commandList.add("true");
        commandList.add("--v3-signing-enabled");
        commandList.add("true");
        commandList.add(apkPath);

        int size = commandList.size();
        String[] commandArray = new String[size];
        commandArray = commandList.toArray(commandArray);
        try {
            ApkSignerTool.main(commandArray);

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }
    public static String generateRotateCerts(String oldStoreFile,String newStoreFile,String oldStorePassword,String newStorePassword){
        ArrayList<String> commandList = new ArrayList<>();

        String lineage = new File(oldStoreFile).getParent() + File.separator + "lineage.jks";
        if(new File(lineage).exists()){
            LogUtils.info("lineage.jks exists");
            return lineage;
        }
        LogUtils.info("generateRotateCerts");
        commandList.add("rotate");
        commandList.add("--out");
        commandList.add(lineage);

        commandList.add("--old-signer");
        commandList.add("--ks");
        commandList.add(oldStoreFile);
        commandList.add("--ks-pass");
        commandList.add("pass:" + oldStorePassword);

        commandList.add("--new-signer");
        commandList.add("--ks");
        commandList.add(newStoreFile);
        commandList.add("--ks-pass");
        commandList.add("pass:" +newStorePassword);

        int size = commandList.size();
        String[] commandArray = new String[size];
        commandArray = commandList.toArray(commandArray);
        try {
            ApkSignerTool.main(commandArray);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return lineage;
    }
}

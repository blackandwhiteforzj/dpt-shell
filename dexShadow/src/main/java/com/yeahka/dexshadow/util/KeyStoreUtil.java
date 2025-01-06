package com.yeahka.dexshadow.util;


import com.yeahka.dexshadow.model.ChannelBean;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class KeyStoreUtil {


    /**
     * 读取签名文件配置信息
     *
     * @param configPath 签名文件路径
     * @return 签名对象
     */
//    public static KeyStore readKeyStoreConfig(String configPath) {
//        File cf = new File(configPath);
//        if (!cf.exists()) {
//            LogUtils.error("签名配置文件不存在");
//            return null;
//        }
//
//        try {
//            List<String> lines = Files.readAllLines(cf.toPath());
//            if (lines.isEmpty()) {
//                LogUtils.error("签名配置文件内容为空");
//                return null;
//            }
//            KeyStore store = new KeyStore();
//            for (String line : lines) {
//                if (line.trim().startsWith("storeFile")) {
//                    store.storeFile = cf.getParent() + File.separator + line.split("=")[1].trim();
//                } else if (line.trim().startsWith("storePassword")) {
//                    store.storePassword = line.split("=")[1].trim();
//                } else if (line.trim().startsWith("alias")) {
//                    store.alias = line.split("=")[1].trim();
//                } else if (line.trim().startsWith("keyPassword")) {
//                    store.keyPassword = line.split("=")[1].trim();
//                } else if (line.trim().startsWith("channelFile")) {
//                    store.channelFile = cf.getParent() + File.separator + line.split("=")[1].trim();
//                }
//            }
//            return store;
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//        return null;
//    }

    public static KeyStore readKeyStoreConfig(String configPath) {
        File cf = new File(configPath);
        if (!cf.exists()) {
            LogUtils.error("签名配置文件不存在");
            return null;
        }

        try {
            List<String> lines = Files.readAllLines(cf.toPath());
            if (lines.isEmpty()) {
                LogUtils.error("签名配置文件内容为空");
                return null;
            }
            KeyStore store = new KeyStore();
            for (String line : lines) {
                if (line.trim().startsWith("oldStoreFile")) {
                    store.oldStoreFile = cf.getParent() + File.separator + line.split("=")[1].trim();
                } else if (line.trim().startsWith("oldStorePassword")) {
                    store.oldStorePassword = line.split("=")[1].trim();
                } else if (line.trim().startsWith("newStoreFile")) {
                    store.newStoreFile = cf.getParent() + File.separator + line.split("=")[1].trim();
                } else if (line.trim().startsWith("newStorePassword")) {
                    store.newStorePassword = line.split("=")[1].trim();
                } else if (line.trim().startsWith("channelFile")) {
                    store.channelFile = cf.getParent() + File.separator + line.split("=")[1].trim();
                }
            }
            return store;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static List<ChannelBean> readChannelFile(KeyStore cfg) {
        List<ChannelBean> channelBeans = new ArrayList<>();
        if (cfg.channelFile != null && new File(cfg.channelFile).exists()) {
            try {
                // 读取文本文件
                BufferedReader reader = new BufferedReader(new FileReader(cfg.channelFile));
                String line;
                // 逐行读取文本内容
                while ((line = reader.readLine()) != null) {
                    // 解析每一行，提取 InstallChannel
                    String[] parts = line.split(" ");
                    if (parts.length >= 3) {
                        String name = parts[0];
                        String market = parts[1];
                        String value = parts[2];
                        channelBeans.add(new ChannelBean(name, market, value));
//                        LogUtils.info("InstallChannel: " + name + " Market: "+market+ "  InstallChannelValue: " + value);
                    }
                }
                reader.close();
                return channelBeans;
            } catch (IOException e) {
                e.printStackTrace();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return channelBeans;
    }
    public static List<ChannelBean> readRotateChannelFile(KeyStore cfg) {
        List<ChannelBean> channelBeans = new ArrayList<>();
        if (cfg.channelFile != null && new File(cfg.channelFile).exists()) {
            try {
                // 读取文本文件
                BufferedReader reader = new BufferedReader(new FileReader(cfg.channelFile));
                String line;
                // 逐行读取文本内容
                while ((line = reader.readLine()) != null) {
                    // 解析每一行，提取 InstallChannel
                    String[] parts = line.split(" ");
                    if (parts.length >= 3) {
                        String name = parts[0];
                        String market = parts[1];
                        String value = parts[2];
                        channelBeans.add(new ChannelBean(name, market, value));
//                        LogUtils.info("InstallChannel: " + name + " Market: "+market+ "  InstallChannelValue: " + value);
                    }
                }
                reader.close();
                return channelBeans;
            } catch (IOException e) {
                e.printStackTrace();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return channelBeans;
    }
}

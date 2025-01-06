package com.yeahka.dexshadow.builder;

import static com.yeahka.dexshadow.util.FileUtils.deleteRecurse;

import com.android.apksigner.ApkSignerTool;
import com.iyxan23.zipalignjava.ZipAlign;
import com.wind.meditor.core.FileProcesser;
import com.wind.meditor.property.AttributeItem;
import com.wind.meditor.property.ModificationProperty;
import com.wind.meditor.utils.NodeValue;
import com.yeahka.dexshadow.Const;
import com.yeahka.dexshadow.elf.ReadElf;
import com.yeahka.dexshadow.model.ChannelBean;
import com.yeahka.dexshadow.model.Instruction;
import com.yeahka.dexshadow.model.MultiDexCode;
import com.yeahka.dexshadow.task.ThreadPool;
import com.yeahka.dexshadow.util.DexUtils;
import com.yeahka.dexshadow.util.FileUtils;
import com.yeahka.dexshadow.util.HexUtils;
import com.yeahka.dexshadow.util.IoUtils;
import com.yeahka.dexshadow.util.KeyStore;
import com.yeahka.dexshadow.util.KeyStoreUtil;
import com.yeahka.dexshadow.util.LogUtils;
import com.yeahka.dexshadow.util.ManifestUtils;
import com.yeahka.dexshadow.util.MultiDexCodeUtils;
import com.yeahka.dexshadow.util.RC4Utils;
import com.yeahka.dexshadow.util.SignUtils;
import com.yeahka.dexshadow.util.ZipUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Copyright © Yeahka All Rights Reserved.
 *
 * @author jimmyzhou
 * @date 2024/6/18
 * @desc 处理apk
 */
public class Apk extends AndroidPackage {
    private boolean dumpCode;

    public static class Builder extends AndroidPackage.Builder {
        private boolean dumpCode;

        @Override
        public Apk build() {
            return new Apk(this);
        }

        public Builder filePath(String path) {
            this.filePath = path;
            return this;
        }

        public Builder packageName(String packageName) {
            this.packageName = packageName;
            return this;
        }

        public Builder debuggable(boolean debuggable) {
            this.debuggable = debuggable;
            return this;
        }

        public Builder sign(boolean sign) {
            this.sign = sign;
            return this;
        }

        public Builder dumpCode(boolean dumpCode) {
            this.dumpCode = dumpCode;
            return this;
        }

        public Builder appComponentFactory(boolean appComponentFactory) {
            this.appComponentFactory = appComponentFactory;
            return this;
        }
        public Builder onlyJiagu(boolean onlyJiagu) {
            this.onlyJiagu = onlyJiagu;
            return this;
        }
        public Builder onlyChannel(boolean onlyChannel) {
            this.onlyChannel = onlyChannel;
            return this;
        }
        public Builder rotateSign(boolean rotateSign) {
            this.rotateSign = rotateSign;
            return this;
        }

    }

    protected Apk(Builder builder) {
        setFilePath(builder.filePath);
        setDebuggable(builder.debuggable);
        setAppComponentFactory(builder.appComponentFactory);
        setSign(builder.sign);
        setPackageName(builder.packageName);
        setDumpCode(builder.dumpCode);
        setOnlyChannel(builder.onlyChannel);
        setOnlyJiagu(builder.onlyJiagu);
        setRotateSign(builder.rotateSign);
    }

    public void setDumpCode(boolean dumpCode) {
        this.dumpCode = dumpCode;
    }

    public boolean isDumpCode() {
        return dumpCode;
    }

    private File getWorkspaceDir() {
        return FileUtils.getDir(Const.ROOT_OF_OUT_DIR, "dexShadowOut");
    }

    public void protect() {
        process(this);
    }

    private static void process(Apk apk) {
        if (!new File("shell-files").exists()) {
            LogUtils.error("Cannot find shell files!");
            return;
        }

        File apkFile = new File(apk.getFilePath());
        if (!apkFile.exists()) {
            LogUtils.error("Apk not exists!");
            return;
        }

        String apkMainProcessPath = apk.getWorkspaceDir().getAbsolutePath();

        LogUtils.info("Apk main process path: " + apkMainProcessPath);

        ZipUtils.unZip(apk.getFilePath(), apkMainProcessPath);
        String packageName = ManifestUtils.getPackageName(apkMainProcessPath + File.separator + "AndroidManifest.xml");
        if(apk.isOnlyChannel()){
            //只打多渠道包
            channelPack(apk,apkFile,false);
            FileUtils.deleteRecurse(new File(apkMainProcessPath));
            return;
        }
        apk.setPackageName(packageName);
        apk.extractDexCode(apkMainProcessPath);


        apk.saveApplicationName(apkMainProcessPath);
        apk.writeProxyAppName(apkMainProcessPath);
        if (apk.isAppComponentFactory()) {
            apk.saveAppComponentFactory(apkMainProcessPath);
            apk.writeProxyComponentFactoryName(apkMainProcessPath);
        }
        if (apk.isDebuggable()) {
            LogUtils.info("Make apk debuggable.");
            apk.setDebuggable(apkMainProcessPath, true);
        }

        apk.setExtractNativeLibs(apkMainProcessPath);

        apk.compressDexFiles(apkMainProcessPath);
        apk.deleteAllDexFiles(apkMainProcessPath);
        apk.combineDexZipWithShellDex(apkMainProcessPath);
        apk.copyNativeLibs(apkMainProcessPath);
        byte[] rc4key = RC4Utils.generateRC4Key();
        apk.encryptSoFiles(apkMainProcessPath,rc4key);

        apk.buildApk(apk,apkFile.getAbsolutePath(), apkMainProcessPath, FileUtils.getExecutablePath());
    }

    private void encryptSoFiles(String apkDir, byte[] rc4Key){
        File obfDir = new File(getOutAssetsDir(apkDir).getAbsolutePath() + File.separator, "vwwwwwvwww");
        File[] soAbiDirs = obfDir.listFiles();
        if(soAbiDirs != null) {
            for (File soAbiDir : soAbiDirs) {
                File[] soFiles = soAbiDir.listFiles();
                if(soFiles != null) {
                    for (File soFile : soFiles) {
                        if(!soFile.getAbsolutePath().endsWith(".so")) {
                            continue;
                        }
                        encryptSoFile(soFile, rc4Key);
                        writeRC4Key(soFile, rc4Key);
                    }
                }
            }
        }

    }

    private void encryptSoFile(File soFile, byte[] rc4Key) {
        try {
            ReadElf readElf = new ReadElf(soFile);
            List<ReadElf.SectionHeader> sectionHeaders = readElf.getSectionHeaders();
            readElf.close();
            for (ReadElf.SectionHeader sectionHeader : sectionHeaders) {
                if(".bitcode".equals(sectionHeader.getName())) {
                    LogUtils.info("start encrypt %s section: %s, offset: %s, size: %s",
                            soFile.getAbsolutePath(),
                            sectionHeader.getName(),
                            HexUtils.toHexString(sectionHeader.getOffset()),
                            sectionHeader.getSize()
                    );

                    byte[] bitcode = IoUtils.readFile(soFile.getAbsolutePath(),
                            sectionHeader.getOffset(),
                            (int)sectionHeader.getSize()
                    );

                    byte[] enc = RC4Utils.crypt(rc4Key, bitcode);
                    IoUtils.writeFile(soFile.getAbsolutePath(),enc,sectionHeader.getOffset());
                }
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void writeRC4Key(File soFile, byte[] rc4key) {
        try {
            ReadElf readElf = new ReadElf(soFile);
            ReadElf.Symbol symbol = readElf.getDynamicSymbol(Const.RC4_KEY_SYMBOL);
            if(symbol == null) {
                LogUtils.warn("cannot find symbol in %s, no need write key", soFile.getName());
                return;
            }
            else {
                LogUtils.info("find symbol(%s) in %s", HexUtils.toHexString(symbol.value), soFile.getName());
            }
            long value = symbol.value;
            int shndx = symbol.shndx;
            List<ReadElf.SectionHeader> sectionHeaders = readElf.getSectionHeaders();
            ReadElf.SectionHeader sectionHeader = sectionHeaders.get(shndx);
            long symbolDataOffset = sectionHeader.getOffset() + value - sectionHeader.getAddr();
            LogUtils.info("write symbol data to %s(%s)", soFile.getName(), HexUtils.toHexString(symbolDataOffset));

            readElf.close();
            IoUtils.writeFile(soFile.getAbsolutePath(),rc4key,symbolDataOffset);
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }
    private static void listAllFiles(File dir, HashMap<String, Long> map) {
        try {
            // 获取文件夹中的所有文件和子文件夹
            File[] files = dir.listFiles();

            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        // 如果是目录，则递归调用
                        listAllFiles(file, map);
                    } else {
                        String dexShadowOut = file.getAbsolutePath().split("dexShadowOut")[1].substring(1).replace("/",".");
                        if (dexShadowOut.startsWith("lib") ||dexShadowOut.startsWith("assets")||
                                dexShadowOut.endsWith(".dex") || dexShadowOut.contains("AndroidManifest.xml")) {
                            LogUtils.info("file-integrity:"+file.getAbsolutePath().split("dexShadowOut")[1].substring(1)+"-----crc:"+ ZipUtils.calFileCRC32(file));
                            map.put(file.getAbsolutePath().split("dexShadowOut")[1].substring(1), ZipUtils.calFileCRC32(file));
                        }

                    }
                }
            }
        } catch (Exception e) {

        }

    }

    /**
     * 将dex的压缩文件与壳dex合并成一个新的dex文件
     */
    private void combineDexZipWithShellDex(String apkMainProcessPath) {
        try {
            File shellDexFile = new File("shell-files/dex/classes.dex");
            File originalDexZipFile = new File(getOutAssetsDir(apkMainProcessPath).getAbsolutePath() + File.separator + "i11111i111.zip");
            byte[] zipData = com.android.dex.util.FileUtils.readFile(originalDexZipFile); // 以二进制形式读出zip
            byte[] unShellDexArray = com.android.dex.util.FileUtils.readFile(shellDexFile); // 以二进制形式读出dex
            int zipDataLen = zipData.length;
            int unShellDexLen = unShellDexArray.length;
            LogUtils.info("zipDataLen: " + zipDataLen);
            LogUtils.info("unShellDexLen: " + unShellDexLen);
            int totalLen = zipDataLen + unShellDexLen + 4; // 多出4字节是存放长度的。
            byte[] newdex = new byte[totalLen]; // 申请了新的长度

            // 添加解壳代码
            System.arraycopy(unShellDexArray, 0, newdex, 0, unShellDexLen); // 先拷贝dex内容
            // 添加未加密的zip数据
            System.arraycopy(zipData, 0, newdex, unShellDexLen, zipDataLen); // 再在dex内容后面拷贝apk的内容
            // 添加解壳数据长度
            System.arraycopy(FileUtils.intToByte(zipDataLen), 0, newdex, totalLen - 4, 4); // 最后4为长度

            // 修改DEX file size文件头
            FileUtils.fixFileSizeHeader(newdex);
            // 修改DEX SHA1 文件头
            FileUtils.fixSHA1Header(newdex);
            // 修改DEX CheckSum文件头
            FileUtils.fixCheckSumHeader(newdex);

            String str = apkMainProcessPath + File.separator + "classes.dex";
            File file = new File(str);
            if (!file.exists()) {
                file.createNewFile();
            }

            // 输出成新的dex文件
            FileOutputStream localFileOutputStream = new FileOutputStream(str);
            localFileOutputStream.write(newdex);
            localFileOutputStream.flush();
            localFileOutputStream.close();
            // 删除dex的zip包
            deleteRecurse(originalDexZipFile);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    /**
     * 对apk进行签名对齐
     *
     * @param originApkPath
     * @param unpackFilePath
     * @param savePath
     */
    private void buildApk(Apk apk,String originApkPath, String unpackFilePath, String savePath) {
        HashMap<String, Long> map = new HashMap<>();
        String originApkName = new File(originApkPath).getName();
        String apkLastProcessDir = getLastProcessDir().getAbsolutePath();

        String unzipalignApkPath = savePath + File.separator + getUnzipalignApkName(originApkName);
        listAllFiles(new File(unpackFilePath), map);
        File test_crc = new File(getOutAssetsDir(unpackFilePath), "test_crc");
        try (FileOutputStream fos = new FileOutputStream(test_crc); ObjectOutputStream oos = new ObjectOutputStream(fos)) {
            oos.writeObject(map);
        } catch (IOException e) {
            e.printStackTrace();
        }

        ZipUtils.zip(unpackFilePath, unzipalignApkPath);

        if(new File("config-files/keystore.cfg").exists()&&new File(Objects.requireNonNull(KeyStoreUtil.readKeyStoreConfig("config-files/keystore.cfg")).oldStoreFile).exists()){
            //使用配置好的签名文件
            channelPack(apk, new File(unzipalignApkPath),true);
        }else {
            //使用内置的debug.keystore
            String keyStoreFilePath = apkLastProcessDir + File.separator + "debug.keystore";
            String keyStoreAssetPath = "assets/debug.keystore";
            try {
                ZipUtils.readResourceFromRuntime(keyStoreAssetPath, keyStoreFilePath);
            } catch (IOException e) {
                e.printStackTrace();
            }
            String unsignedApkPath = savePath + File.separator + getUnsignApkName(originApkName);
            boolean zipalignSuccess = false;
            try {
                zipalignApk(unzipalignApkPath, unsignedApkPath);
                zipalignSuccess = true;
            } catch (Exception e) {
                e.printStackTrace();
            }

            String willSignApkPath = null;
            if (zipalignSuccess) {
                LogUtils.info("zipalign success.");
                willSignApkPath = unsignedApkPath;

            } else {
                LogUtils.error("warning: zipalign failed!");
                willSignApkPath = unzipalignApkPath;
            }

            boolean signResult = false;

            String signedApkPath = savePath + File.separator + getSignedApkName(originApkName);

            if (isSign()) {
                signResult = signApkDebug(willSignApkPath, keyStoreFilePath, signedApkPath);
            }

            File willSignApkFile = new File(willSignApkPath);
            File signedApkFile = new File(signedApkPath);
            File keyStoreFile = new File(keyStoreFilePath);
            File idsigFile = new File(signedApkPath + ".idsig");


            LogUtils.info("willSignApkFile: %s ,exists: %s", willSignApkFile.getAbsolutePath(), willSignApkFile.exists());
            LogUtils.info("signedApkFile: %s ,exists: %s", signedApkFile.getAbsolutePath(), signedApkFile.exists());

            String resultPath = signedApkFile.getAbsolutePath();
            if (!signedApkFile.exists() || !signResult) {
                resultPath = willSignApkFile.getAbsolutePath();
            } else {
                if (willSignApkFile.exists()) {
                    willSignApkFile.delete();
                }
            }

            if (zipalignSuccess) {
                File unzipalignApkFile = new File(unzipalignApkPath);
                try {
                    Path filePath = Paths.get(unzipalignApkFile.getAbsolutePath());
                    Files.deleteIfExists(filePath);
                } catch (Exception e) {
                    LogUtils.debug("unzipalignApkPath err = %s", e);
                }
            }

            if (idsigFile.exists()) {
                idsigFile.delete();
            }

            if (keyStoreFile.exists()) {
                keyStoreFile.delete();
            }
            LogUtils.info("protected apk output path: " + resultPath + "\n");
        }
    }
    public static void channelPack(Apk apk, File apkFile,boolean isZipalign){
        File out = new File("output");
        if(out.exists()){
            deleteRecurse(out);
        }
        out.mkdir();
        KeyStore store =  KeyStoreUtil.readKeyStoreConfig("config-files/keystore.cfg");;

        List<ChannelBean> channelBeans = KeyStoreUtil.readRotateChannelFile(store);
        if(channelBeans.isEmpty()||apk.isOnlyJiagu()){
            //只签名 不打多渠道
            SignUtils.realSign(apk.isRotateSign(),out.getAbsolutePath(),apkFile, store,apkFile,false,isZipalign);
        }else {
            //签名+多渠道打包
            for (ChannelBean channelBean : channelBeans) {
                File unSignedFile = ManifestUtils.addMetaData(channelBean.name, channelBean.value.trim().replace(" ",""), apkFile.getAbsolutePath(), "new_build_unsigned_"+channelBean.market+".apk");
                SignUtils.realSign(apk.isRotateSign(),out.getAbsolutePath(),unSignedFile, store,apkFile,true,isZipalign);
            }
        }
        if(isZipalign){
            apkFile.delete();
        }
    }
    private static boolean signApk(String apkPath, String keyStorePath, String signedApkPath, String keyAlias, String storePassword, String KeyPassword) {
        ArrayList<String> commandList = new ArrayList<>();

        commandList.add("sign");
        commandList.add("--ks");
        commandList.add(keyStorePath);
        commandList.add("--ks-key-alias");
        commandList.add(keyAlias);
        commandList.add("--ks-pass");
        commandList.add("pass:" + storePassword);
        commandList.add("--key-pass");
        commandList.add("pass:" + KeyPassword);
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

    private boolean signApkDebug(String willSignApkPath, String keyStoreFilePath, String signedApkPath) {
        if (signApk(willSignApkPath, keyStoreFilePath, signedApkPath, Const.KEY_ALIAS, Const.STORE_PASSWORD, Const.KEY_PASSWORD)) {
            return true;
        }
        return false;
    }

    private String getSignedApkName(String originApkName) {
        return FileUtils.getNewFileName(originApkName, "signed");
    }

    private void zipalignApk(String inputApkPath, String outputApkPath) throws Exception {
        RandomAccessFile in = new RandomAccessFile(inputApkPath, "r");
        FileOutputStream out = new FileOutputStream(outputApkPath);
        ZipAlign.alignZip(in, out);
        IoUtils.close(in);
        IoUtils.close(out);
    }

    private String getUnsignApkName(String originApkName) {
        return FileUtils.getNewFileName(originApkName, "unsign");
    }

    private String getUnzipalignApkName(String originApkName) {
        return FileUtils.getNewFileName(originApkName, "unzipalign");
    }

    private File getLastProcessDir() {
        return FileUtils.getDir(Const.ROOT_OF_OUT_DIR, "dexShadowLastProcess");
    }

    /**
     * 将壳工程生成的so库拷贝到assets目录下
     *
     * @param apkMainProcessPath
     */
    private void copyNativeLibs(String apkMainProcessPath) {
        File file = new File(FileUtils.getExecutablePath(), "shell-files/libs");
        FileUtils.copy(file.getAbsolutePath(), getOutAssetsDir(apkMainProcessPath).getAbsolutePath() + File.separator + "vwwwwwvwww");
    }

    /**
     * 将壳的dex添加到原apk中
     *
     * @param apkMainProcessPath
     */
    private void addProxyDex(String apkMainProcessPath) {
        String proxyDexPath = "shell-files/dex/classes.dex";
        addDex(proxyDexPath, apkMainProcessPath);
    }

    private void addDex(String proxyDexPath, String apkMainProcessPath) {
        File dexFile = new File(proxyDexPath);
        List<File> dexFiles = getDexFiles(apkMainProcessPath);
        int newDexNameNumber = dexFiles.size() + 1;
        String newDexPath = apkMainProcessPath + File.separator + "classes.dex";
        if (newDexNameNumber > 1) {
            newDexPath = apkMainProcessPath + File.separator + String.format(Locale.US, "classes%d.dex", newDexNameNumber);
        }
        byte[] dexData = IoUtils.readFile(dexFile.getAbsolutePath());
        IoUtils.writeFile(newDexPath, dexData);
    }

    /**
     * 修改AndroidManifest.xml的extractNativeLibs为true
     *
     * @param apkMainProcessPath
     */
    private void setExtractNativeLibs(String apkMainProcessPath) {

        String inManifestPath = apkMainProcessPath + File.separator + "AndroidManifest.xml";
        String outManifestPath = apkMainProcessPath + File.separator + "AndroidManifest_new.xml";
        ModificationProperty property = new ModificationProperty();

        property.addApplicationAttribute(new AttributeItem(NodeValue.Application.EXTRACTNATIVELIBS, "true"));

        FileProcesser.processManifestFile(inManifestPath, outManifestPath, property);

        File inManifestFile = new File(inManifestPath);
        File outManifestFile = new File(outManifestPath);

        inManifestFile.delete();

        outManifestFile.renameTo(inManifestFile);
    }

    /**
     * 修改AndroidManifest.xml的debuggable为true
     *
     * @param filePath
     * @param debuggable
     */
    private void setDebuggable(String filePath, boolean debuggable) {
        String inManifestPath = filePath + File.separator + "AndroidManifest.xml";
        String outManifestPath = filePath + File.separator + "AndroidManifest_new.xml";
        ManifestUtils.writeDebuggable(inManifestPath, outManifestPath, debuggable ? "true" : "false");

        File inManifestFile = new File(inManifestPath);
        File outManifestFile = new File(outManifestPath);

        inManifestFile.delete();

        outManifestFile.renameTo(inManifestFile);
    }

    /**
     * 将壳的ComponentFactory写入到原apk的AndroidManifest.xml(如果已存在则替换)
     *
     * @param apkMainProcessPath
     */
    private void writeProxyComponentFactoryName(String apkMainProcessPath) {
        String inManifestPath = apkMainProcessPath + File.separator + "AndroidManifest.xml";
        String outManifestPath = apkMainProcessPath + File.separator + "AndroidManifest_new.xml";
        ManifestUtils.writeAppComponentFactory(inManifestPath, outManifestPath, Const.PROXY_COMPONENT_FACTORY);

        File inManifestFile = new File(inManifestPath);
        File outManifestFile = new File(outManifestPath);

        inManifestFile.delete();

        outManifestFile.renameTo(inManifestFile);
    }

    /**
     * 将原apk的appComponentFactory写入文件保存
     *
     * @param apkMainProcessPath
     */
    private void saveAppComponentFactory(String apkMainProcessPath) {
        String androidManifestFile = getManifestFilePath(apkMainProcessPath);
        File appNameOutFile = new File(getOutAssetsDir(apkMainProcessPath), "app_acf");
        String appName = ManifestUtils.getAppComponentFactory(androidManifestFile);

        appName = appName == null ? "" : appName;

        IoUtils.writeFile(appNameOutFile.getAbsolutePath(), appName.getBytes());
    }

    /**
     * 将壳的application写入到原apk的AndroidManifest.xml(如果已存在则替换)
     *
     * @param apkMainProcessPath
     */
    private void writeProxyAppName(String apkMainProcessPath) {
        String inManifestPath = apkMainProcessPath + File.separator + "AndroidManifest.xml";
        String outManifestPath = apkMainProcessPath + File.separator + "AndroidManifest_new.xml";
        ManifestUtils.writeApplicationName(inManifestPath, outManifestPath, Const.PROXY_APPLICATION_NAME);

        File inManifestFile = new File(inManifestPath);
        File outManifestFile = new File(outManifestPath);

        inManifestFile.delete();

        outManifestFile.renameTo(inManifestFile);
    }

    /**
     * 将原apk的applicationName写到文件里面保存
     *
     * @param apkMainProcessPath
     */
    private void saveApplicationName(String apkMainProcessPath) {
        String androidManifestFile = getManifestFilePath(apkMainProcessPath);
        File appNameOutFile = new File(getOutAssetsDir(apkMainProcessPath), "app_name");
        String appName = ManifestUtils.getApplicationName(androidManifestFile);

        appName = appName == null ? "" : appName;

        IoUtils.writeFile(appNameOutFile.getAbsolutePath(), appName.getBytes());
    }

    /**
     * 获取AndroidManifest.xml的路径
     *
     * @param apkMainProcessPath
     * @return
     */
    private String getManifestFilePath(String apkMainProcessPath) {
        return apkMainProcessPath + File.separator + "AndroidManifest.xml";
    }

    /**
     * 将原apk的dex都删除
     *
     * @param apkMainProcessPath
     */
    private void deleteAllDexFiles(String apkMainProcessPath) {
        List<File> dexFiles = getDexFiles(apkMainProcessPath);
        for (File dexFile : dexFiles) {
            dexFile.delete();
        }
    }

    /**
     * 将抽取完指令的dex文件压缩成zip文件存储在assets目录下
     *
     * @param apkMainProcessPath
     */
    private void compressDexFiles(String apkMainProcessPath) {
        ZipUtils.compress(getDexFiles(apkMainProcessPath), getOutAssetsDir(apkMainProcessPath).getAbsolutePath() + File.separator + "i11111i111.zip");
    }

    /**
     * 提取dex文件中的方法指令到指定文件
     *
     * @param apkOutDir
     */
    private void extractDexCode(String apkOutDir) {
        List<File> dexFiles = getDexFiles(apkOutDir);
        Map<Integer, List<Instruction>> instructionMap = new HashMap<>();
        String dataOutputPath = getOutAssetsDir(apkOutDir).getAbsolutePath() + File.separator + "OoooooOooo";
        CountDownLatch countDownLatch = new CountDownLatch(dexFiles.size());
        for (File dexFile : dexFiles) {
            ThreadPool.getInstance().execute(() -> {
                final int dexNo = getDexNumber(dexFile.getName());
                if (dexNo < 0) {
                    return;
                }
                String extractedDexName = dexFile.getName().endsWith(".dex") ? dexFile.getName().replaceAll("\\.dex$", "_extracted.dat") : "_extracted.dat";
                //创建文件 classes1_extracted.dat 文件
                File extractedDexFile = new File(dexFile.getParent(), extractedDexName);
                List<Instruction> ret = DexUtils.extractAllMethods(dexFile, extractedDexFile, getPackageName(), isDumpCode());
                instructionMap.put(dexNo, ret);
                //创建哈希文件对象
                File dexFileRightHashes = new File(dexFile.getParent(), FileUtils.getNewFileSuffix(dexFile.getName(), "dat"));
                //写入哈希
                DexUtils.writeHashes(extractedDexFile, dexFileRightHashes);
                //删除原始 .dex 文件
                dexFile.delete();
                //删除提取后的 .dex 文件
                extractedDexFile.delete();
                //重命名新文件为原始文件名
                dexFileRightHashes.renameTo(dexFile);
                countDownLatch.countDown();
            });
        }
        ThreadPool.getInstance().shutdown();

        try {
            countDownLatch.await();
        } catch (Exception ignored) {
        }

        MultiDexCode multiDexCode = MultiDexCodeUtils.makeMultiDexCode(instructionMap);

        MultiDexCodeUtils.writeMultiDexCode(dataOutputPath, multiDexCode);
    }

    /**
     * 获取所有的.dex文件
     *
     * @param apkOutDir
     * @return
     */
    private List<File> getDexFiles(String apkOutDir) {
        List<File> dexFiles = new ArrayList<>();
        File dirFile = new File(apkOutDir);
        File[] files = dirFile.listFiles();
        if (files != null) {
            Arrays.stream(files).filter(it -> it.getName().endsWith(".dex")).forEach(dexFiles::add);
        }
        return dexFiles;
    }

    /**
     * 获取assets目录
     *
     * @param filePath
     * @return
     */
    private File getOutAssetsDir(String filePath) {
        return FileUtils.getDir(filePath, "assets");
    }

    /**
     * 获取dex文件的编号 比如:classes2.dex 返回 1
     *
     * @param dexName
     * @return
     */
    private int getDexNumber(String dexName) {
        Pattern pattern = Pattern.compile("classes(\\d*)\\.dex$");
        Matcher matcher = pattern.matcher(dexName);
        if (matcher.find()) {
            String dexNo = matcher.group(1);
            return (dexNo == null || "".equals(dexNo)) ? 0 : Integer.parseInt(dexNo) - 1;
        } else {
            return -1;
        }
    }

}

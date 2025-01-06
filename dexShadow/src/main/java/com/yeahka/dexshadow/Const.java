package com.yeahka.dexshadow;

/**
 * Copyright © Yeahka All Rights Reserved.
 *
 * @author jimmyzhou
 * @date 2024/6/18
 * @desc 常量
 */
public class Const {
    public static final String OPTION_OPEN_NOISY_LOG_LONG = "noisy-log";
    public static final String OPTION_OPEN_NOISY_LOG = "l";
    public static final String OPTION_NO_SIGN_APK_LONG = "no-sign";
    public static final String OPTION_NO_SIGN_APK = "x";
    public static final String OPTION_DUMP_CODE_LONG = "dump-code";
    public static final String OPTION_DUMP_CODE = "d";
    public static final String OPTION_ONLY_JIAGU = "jiagu";
    public static final String OPTION_ONLY_JIAGU_LONG = "only-jiagu";
    public static final String OPTION_ONLY_CHANNEL_PACK = "channel";
    public static final String OPTION_ONLY_CHANNEL_PACK_LONG = "only-channel-pack";

    public static final String OPTION_ROTATE_SIGN = "rotate";
    public static final String OPTION_ROTATE_SIGN_LONG = "rotate-sign";

    public static final String OPTION_APK_FILE = "f";
    public static final String OPTION_APK_FILE_LONG = "apk-file";
    public static final String OPTION_DEBUGGABLE = "D";
    public static final String OPTION_DEBUGGABLE_LONG = "debug";
    public static final String OPTION_DISABLE_APP_COMPONENT_FACTORY = "c";
    public static final String OPTION_DISABLE_APP_COMPONENT_FACTORY_LONG = "disable-acf";

    public static final String STORE_PASSWORD = "android";
    public static final String KEY_PASSWORD = "android";
    public static final String KEY_ALIAS = "androiddebugkey";
    public static final String DEFAULT_THREAD_NAME = "dexShadow";

    public static final String ROOT_OF_OUT_DIR = System.getProperty("java.io.tmpdir");
    public static final String PROXY_APPLICATION_NAME = "com.yeahka.shell.ProxyApplication";
    public static final String PROXY_COMPONENT_FACTORY = "com.yeahka.shell.ProxyComponentFactory";

    public static final String META_APPLICATION_NAME = "APPLICATION_NAME";

    public static final String META_COMPONENT_FACTORY = "COMPONENT_FACTORY";


    public static final short MULTI_DEX_CODE_VERSION = 1;
    public static final String RC4_KEY_SYMBOL = "DPT_UNKNOWN_DATA";

}

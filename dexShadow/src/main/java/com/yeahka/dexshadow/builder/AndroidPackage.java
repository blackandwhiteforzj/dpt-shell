package com.yeahka.dexshadow.builder;

/**
 * Copyright © Yeahka All Rights Reserved.
 *
 * @author jimmyzhou
 * @date 2024/6/18
 * @desc 抽象出一些方法
 */
public abstract class AndroidPackage {

    protected static abstract class Builder {
        public String filePath = null;
        public String packageName = null;
        public boolean debuggable = false;
        public boolean sign = true;
        public boolean appComponentFactory = true;



        public boolean onlyJiagu = false;
        public boolean onlyChannel = false;
        public boolean rotateSign = false;
        public abstract AndroidPackage build();
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public boolean isDebuggable() {
        return debuggable;
    }

    public void setDebuggable(boolean debuggable) {
        this.debuggable = debuggable;
    }

    public boolean isSign() {
        return sign;
    }

    public void setSign(boolean sign) {
        this.sign = sign;
    }

    public boolean isAppComponentFactory() {
        return appComponentFactory;
    }

    public void setAppComponentFactory(boolean appComponentFactory) {
        this.appComponentFactory = appComponentFactory;
    }
    public boolean isOnlyJiagu() {
        return onlyJiagu;
    }

    public void setOnlyJiagu(boolean onlyJiagu) {
        this.onlyJiagu = onlyJiagu;
    }

    public boolean isOnlyChannel() {
        return onlyChannel;
    }

    public void setOnlyChannel(boolean onlyChannel) {
        this.onlyChannel = onlyChannel;
    }
    public boolean isRotateSign() {
        return rotateSign;
    }

    public void setRotateSign(boolean rotateSign) {
        this.rotateSign = rotateSign;
    }
    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String filePath = null;

    public String packageName = null;
    public boolean debuggable = false;
    public boolean sign = true;
    public boolean appComponentFactory = true;
    public boolean onlyJiagu = false;
    public boolean onlyChannel = false;



    public boolean rotateSign = false;
}

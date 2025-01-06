package com.yeahka.dexshadow.model;

import java.util.List;

/**
 * Copyright © Yeahka All Rights Reserved.
 *
 * @author jimmyzhou
 * @date 2024/7/3
 * @desc 多dex处理
 */
public class MultiDexCode {
    //File version
    private short version;
    //Dex count
    private short dexCount;
    //用于存储每个 DEX 文件的起始位置
    private List<Integer> dexCodesIndex;
    //用于存储 DexCode 对象
    private List<DexCode> dexCodes;

    public short getVersion() {
        return version;
    }

    public void setVersion(short version) {
        this.version = version;
    }

    public short getDexCount() {
        return dexCount;
    }

    public void setDexCount(short dexCount) {
        this.dexCount = dexCount;
    }

    public List<Integer> getDexCodesIndex() {
        return dexCodesIndex;
    }

    public void setDexCodesIndex(List<Integer> dexCodesIndex) {
        this.dexCodesIndex = dexCodesIndex;
    }

    public List<DexCode> getDexCodes() {
        return dexCodes;
    }

    public void setDexCodes(List<DexCode> dexCodes) {
        this.dexCodes = dexCodes;
    }

    @Override
    public String toString() {
        return "MultiDexCode{" +
                "version=" + version +
                ", dexCount=" + dexCount +
                ", dexCodesIndex=" + dexCodesIndex +
                ", dexCodes=" + dexCodes +
                '}';
    }
}

package com.yeahka.dexshadow.model;

import java.util.List;

/**
 * Copyright © Yeahka All Rights Reserved.
 *
 * @author jimmyzhou
 * @date 2024/7/3
 * @desc DexCode
 */
public class DexCode {
    //dex文件里面的方法数量
    private Short methodCount;
    //用于存储方法指令数据的在dex文件中的索引，也就是偏移量
    private List<Integer> insnsIndex;
    //用于存储方法指令
    private List<Instruction> insns;

    public Short getMethodCount() {
        return methodCount;
    }

    public void setMethodCount(Short methodCount) {
        this.methodCount = methodCount;
    }

    public List<Integer> getInsnsIndex() {
        return insnsIndex;
    }

    public void setInsnsIndex(List<Integer> insnsIndex) {
        this.insnsIndex = insnsIndex;
    }

    public List<Instruction> getInsns() {
        return insns;
    }

    public void setInsns(List<Instruction> insns) {
        this.insns = insns;
    }

    @Override
    public String toString() {
        return "DexCode{" +
                "methodCount=" + methodCount +
                ", insnsIndex=" + insnsIndex +
                ", insns=" + insns +
                '}';
    }
}

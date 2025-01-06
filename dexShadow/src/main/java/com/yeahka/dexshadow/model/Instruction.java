package com.yeahka.dexshadow.model;

import java.util.Arrays;

/**
 * Copyright © Yeahka All Rights Reserved.
 *
 * @author jimmyzhou
 * @date 2024/6/25
 * @desc 用来记录dex文件方法的codeItem
 */
public class Instruction {
    private int offsetOfDex;

    private int methodIndex;

    private int instructionDataSize;

    private byte[] instructionsData;

    public int getOffsetOfDex() {
        return offsetOfDex;
    }

    public void setOffsetOfDex(int offsetOfDex) {
        this.offsetOfDex = offsetOfDex;
    }

    public int getMethodIndex() {
        return methodIndex;
    }

    public void setMethodIndex(int methodIndex) {
        this.methodIndex = methodIndex;
    }

    public int getInstructionDataSize() {
        return instructionDataSize;
    }

    public void setInstructionDataSize(int instructionDataSize) {
        this.instructionDataSize = instructionDataSize;
    }

    public byte[] getInstructionsData() {
        return instructionsData;
    }

    public void setInstructionsData(byte[] instructionsData) {
        this.instructionsData = instructionsData;
    }

    @Override
    public String toString() {
        return "Instruction{" +
                "offsetOfDex=" + offsetOfDex +
                ", methodIndex=" + methodIndex +
                ", instructionDataSize=" + instructionDataSize +
                ", instructionsData=" + Arrays.toString(instructionsData) +
                '}';
    }
}

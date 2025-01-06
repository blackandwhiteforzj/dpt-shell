package com.yeahka.dexshadow.util;

import com.yeahka.dexshadow.Const;
import com.yeahka.dexshadow.model.DexCode;
import com.yeahka.dexshadow.model.Instruction;
import com.yeahka.dexshadow.model.MultiDexCode;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Copyright © Yeahka All Rights Reserved.
 *
 * @author jimmyzhou
 * @date 2024/7/3
 * @desc 多dex处理工具类
 */
public class MultiDexCodeUtils {
    /**
     * 根据抽取出来的指令封装成MultiDexCode
     * @param multiDexInsns
     * @return
     */
    public static MultiDexCode makeMultiDexCode(Map<Integer, List<Instruction>> multiDexInsns) {
        //初始化文件偏移量为 0。这个偏移量用于计算每个 DEX 文件的起始位置
        int fileOffset = 0;

        //创建一个新的 MultiDexCode 对象。
        //设置 MultiDexCode 对象的版本号:1
        MultiDexCode multiDexCode = new MultiDexCode();
        multiDexCode.setVersion(Const.MULTI_DEX_CODE_VERSION);
        //增加文件偏移量，因为版本号是 short 类型，占用 2 个字节。
        fileOffset += 2;

        //设置 DEX 文件的数量。使用 multiDexInsns.size() 获取 Map 中的条目数，并将其转换为 short 类型。
        //增加文件偏移量 2，因为 DEX 文件的数量是 short 类型，占用 2 个字节。
        multiDexCode.setDexCount((short) multiDexInsns.size());
        fileOffset += 2;

        //创建一个 dexCodeIndex 列表，用于存储每个 DEX 文件的起始位置。
        //将这个列表设置到 MultiDexCode 对象中。
        //根据 DEX 文件的数量调整文件偏移量。每个偏移量占用 4 个字节，因此需要乘以 DEX 文件的数量。
        List<Integer> dexCodeIndex = new ArrayList<>();
        multiDexCode.setDexCodesIndex(dexCodeIndex);
        fileOffset += 4 * multiDexInsns.size();

        //初始化两个列表，一个用于存储 DexCode 对象，另一个用于存储指令的索引。
        List<DexCode> dexCodeList = new ArrayList<>();
        List<Integer> insnsIndexList = new ArrayList<>();

        //创建一个迭代器，用于遍历 multiDexInsns 中的每个条目。
        Iterator<Map.Entry<Integer, List<Instruction>>> iterator = multiDexInsns.entrySet()
                .iterator();
        while (iterator.hasNext()) {
            //获取每个条目的 Instruction 列表。
            List<Instruction> insns = iterator.next()
                    .getValue();
            LogUtils.info("DexCode offset = " + fileOffset);
            if (insns == null) {
                continue;
            }
            //将当前的 fileOffset 添加到 dexCodeIndex 列表中。
            //创建一个新的 DexCode 对象。
            dexCodeIndex.add(fileOffset);
            DexCode dexCode = new DexCode();

            //设置当前 DexCode 对象的方法数量。使用 insns.size() 获取指令的数量，并将其转换为 short 类型。
            //增加文件偏移量 2，因为方法数量是 short 类型，占用 2 个字节。
            dexCode.setMethodCount((short) insns.size());
            fileOffset += 2;
            //设置 DexCode 对象的指令列表
            dexCode.setInsns(insns);

            //将当前的文件偏移量添加到指令索引列表中。
            //将指令索引列表设置到 DexCode 对象中。
            insnsIndexList.add(fileOffset);
            dexCode.setInsnsIndex(insnsIndexList);

            //遍历每个指令，逐步增加 fileOffset，分别计算每个指令的偏移量、方法索引、指令数据大小和实际的指令数据长度。
            for (Instruction ins : insns) {
                fileOffset += 4; //指令数据的偏移量offsetOfDex
                fileOffset += 4; //方法id的偏移量methodIndex
                fileOffset += 4; //指令数据的大小instructionDataSize
                fileOffset += ins.getInstructionsData().length; //Instruction.instructionsData
            }
            //将处理好的 DexCode 对象添加到 dexCodeList 列表中。
            dexCodeList.add(dexCode);
        }
        LogUtils.info("fileOffset = " + fileOffset);
        //将所有 DexCode 对象设置到 MultiDexCode 对象中。
        multiDexCode.setDexCodes(dexCodeList);

        return multiDexCode;
    }

    /**
     * 将封装的multiDexCode写到文件中
     * @param out
     * @param multiDexCode
     */
    public static void writeMultiDexCode(String out, MultiDexCode multiDexCode) {
        if (multiDexCode.getDexCodes()
                .isEmpty()) {
            return;
        }
        RandomAccessFile randomAccessFile = null;

        try {
            //声明一个 RandomAccessFile 对象，用于随机访问文件。
            randomAccessFile = new RandomAccessFile(out, "rw");
            //将文件版本号写入文件，使用 Endian.makeLittleEndian 方法确保数据是小端序（Little Endian）。
            randomAccessFile.write(Endian.makeLittleEndian(multiDexCode.getVersion()));
            //将 DEX 文件的数量写入文件。
            randomAccessFile.write(Endian.makeLittleEndian(multiDexCode.getDexCount()));

            //将每个 DEX 文件的起始位置写入文件
            for (Integer dexCodesIndex : multiDexCode.getDexCodesIndex()) {
                randomAccessFile.write(Endian.makeLittleEndian(dexCodesIndex));
            }
            //遍历 MultiDexCode 对象中的每个 DexCode。
            //获取 DexCode 对象中的指令列表，并获取方法数量。
            for (DexCode dexCode : multiDexCode.getDexCodes()) {
                List<Instruction> insns = dexCode.getInsns();
                //如果我们想将 short 类型的值视为无符号整数，我们可以使用 & 0xFFFF 来将其扩展到 int 类型
                int methodCount = dexCode.getMethodCount() & 0xFFFF;

                LogUtils.info("insns item count:" + insns.size() + ",method count : " + methodCount);
                //将方法数量写入文件。
                randomAccessFile.write(Endian.makeLittleEndian(dexCode.getMethodCount()));
                //将每条指令的相关信息（方法索引、DEX 偏移量、指令数据大小、指令数据）逐一写入文件。
                for (int i = 0; i < insns.size(); i++) {
                    Instruction instruction = insns.get(i);
                    randomAccessFile.write(Endian.makeLittleEndian(instruction.getMethodIndex()));
                    randomAccessFile.write(Endian.makeLittleEndian(instruction.getOffsetOfDex()));
                    randomAccessFile.write(Endian.makeLittleEndian(instruction.getInstructionDataSize()));
                    randomAccessFile.write(instruction.getInstructionsData());
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            IoUtils.close(randomAccessFile);
        }
    }
}

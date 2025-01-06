package com.yeahka.dexshadow.util;

import com.android.dex.ClassData;
import com.android.dex.ClassDef;
import com.android.dex.Code;
import com.android.dex.Dex;
import com.android.dex.MethodId;
import com.android.dex.ProtoId;
import com.yeahka.dexshadow.model.Instruction;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Copyright © Yeahka All Rights Reserved.
 *
 * @author jimmyzhou
 * @date 2024/7/2
 * @desc 处理dex文件
 */
public class DexUtils {
    /* 这些类不抽取 */
    private static final String[] excludeRule = {
            "Landroid/.*",
            "Landroidx/.*",
            "Lcom/squareup/okhttp/.*",
            "Lokio/.*", "Lokhttp3/.*",
            "Lkotlin/.*",
            "Lcom/google/.*",
            "Lrx/.*",
            "Lorg/apache/.*",
            "Lretrofit2/.*",
            "Lcom/alibaba/.*",
            "Lcom/amap/api/.*",
            "Lcom/sina/weibo/.*",
            "Lcom/xiaomi/.*",
            "Lcom/eclipsesource/.*",
            "Lcom/blankj/utilcode/.*",
            "Lcom/umeng/.*",
            "Ljavax/.*",
            "Lorg/slf4j/.*"
    };

    /**
     * 处理所有方法 提取codeItem并将提取记录保存到json
     * @param dexFile
     * @param outDexFile
     * @param packageName
     * @param dumpCode
     * @return
     */
    public static List<Instruction> extractAllMethods(File dexFile, File outDexFile, String packageName, boolean dumpCode) {
        List<Instruction> instructionList = new ArrayList<>();
        Dex dex = null;
        RandomAccessFile randomAccessFile = null;
        //将classes1.dex文件的内容写入到classes1_extracted.dat文件
        byte[] dexData = IoUtils.readFile(dexFile.getAbsolutePath());
        IoUtils.writeFile(outDexFile.getAbsolutePath(),dexData);
        JSONArray dumpJSON = new JSONArray();
        try {
            //将原dexFile(classes1.dex)封装成Dex对象
            dex = new Dex(dexFile);
            //将outDexFile(classes1_extracted.dat)封装为RandomAccessFile对象
            randomAccessFile = new RandomAccessFile(outDexFile,"rw");
            /**
             * 获取dex文件对象的classDefs
             * class_idx
             * access_flags
             * superclass_idx
             * interfaces_off
             * source_file_idx
             * annotations_off
             * class_data_off
             * static_values_off
             * class_data[]
             *
             */
            Iterable<ClassDef> classDefs = dex.classDefs();
            for (ClassDef classDef:classDefs){
                boolean skip = false;
                //跳过不抽取的类
                for(String rule : excludeRule){
                    if(classDef.toString().matches(rule)){
                        skip = true;
                        break;
                    }
                }
                if(skip){
                    continue;
                }
                if(classDef.getClassDataOffset() == 0){
                    LogUtils.noisy("class '%s' data offset is zero",classDef.toString());
                    continue;
                }

                JSONObject classJSONObject = new JSONObject();
                JSONArray classJSONArray = new JSONArray();
                //获取 class_data[]
                ClassData classData = dex.readClassData(classDef);
                //获取类名
                String className = dex.typeNames().get(classDef.getTypeIndex());
                //类名转换 Ljava/lang/System; -> java.lang.System
                String humanizeTypeName = TypeUtils.getHumanizeTypeName(className);
                //获取类的所有方法 DirectMethods 和 VirtualMethods
                ClassData.Method[] directMethods = classData.getDirectMethods();
                ClassData.Method[] virtualMethods = classData.getVirtualMethods();
                //遍历方法
                for (ClassData.Method method:directMethods){
                    //提取每个方法的codeItem并用nop填充
                    Instruction instruction = extractMethod(dex,randomAccessFile,classDef,method);
                    if(instruction != null) {
                        instructionList.add(instruction);
                        putToJSON(classJSONArray, instruction);
                    }
                }

                for (ClassData.Method method:virtualMethods){
                    Instruction instruction = extractMethod(dex, randomAccessFile,classDef, method);
                    if(instruction != null) {
                        instructionList.add(instruction);
                        putToJSON(classJSONArray, instruction);
                    }
                }

                classJSONObject.put(humanizeTypeName,classJSONArray);
                dumpJSON.put(classJSONObject);

            }
        }catch (Exception e){
            LogUtils.error("error occurred");
        } finally {
            IoUtils.close(randomAccessFile);
            if(dumpCode) {
                dumpJSON(packageName,dexFile, dumpJSON);
            }
        }
        return instructionList;
    }

    /**
     *  将dump出来的类和对应的method写入文件
     * @param packageName
     * @param originFile
     * @param dumpJSON
     */
    private static void dumpJSON(String packageName, File originFile, JSONArray dumpJSON) {
        File pkg = new File(packageName);
        if(!pkg.exists()){
            pkg.mkdirs();
        }
        File writePath = new File(pkg.getAbsolutePath(),originFile.getName() + ".json");
        LogUtils.info("dump json to path: %s",writePath.getParentFile().getName() + File.separator + writePath.getName());

        IoUtils.writeFile(writePath.getAbsolutePath(),dumpJSON.toString(1).getBytes());
    }

    /**
     * 保存instruction到json
     * @param classJSONArray
     * @param instruction
     */
    private static void putToJSON(JSONArray classJSONArray, Instruction instruction) {
        JSONObject jsonObject = new JSONObject();
        String hex = HexUtils.toHexArray(instruction.getInstructionsData());
        jsonObject.put("methodId",instruction.getMethodIndex());
        jsonObject.put("code",hex);
        classJSONArray.put(jsonObject);
    }

    /**
     * 提取指定方法的codeItem并用nop填充
     * @param dex
     * @param outRandomAccessFile
     * @param classDef
     * @param method
     * @return
     */
    private static Instruction extractMethod(Dex dex, RandomAccessFile outRandomAccessFile, ClassDef classDef, ClassData.Method method) throws IOException {
        //获取所有的string列表
        List<String> strings = dex.strings();
        //获取所有的类型名称
        List<String> typeNames = dex.typeNames();
        //获取方法原型ID列表
        List<ProtoId> protoIds = dex.protoIds();
        //获取方法ID列表
        List<MethodId> methodIds = dex.methodIds();
        //获取method的method_idx_diff 表示相对于上一个方法的索引增量
        int methodIndex = method.getMethodIndex();
        //再从方法ID列表中获取对应method_idx_diff的 methodId
        MethodId methodId = methodIds.get(methodIndex);
        //获取MethodId的proto_idx
        int protoIndex = methodId.getProtoIndex();
        //根据proto_idx 获取对应的protoId
        ProtoId protoId = protoIds.get(protoIndex);
        //获取ProtoId的returnTypeIndex字段
        int returnTypeIndex = protoId.getReturnTypeIndex();
        //根据returnTypeIndex获取returnTypeName
        String returnTypeName = typeNames.get(returnTypeIndex);
        //获取MethodId的nameIndex
        int nameIndex = methodId.getNameIndex();
        //从string列表里面根据nameIndex获取对应methodName
        String methodName = strings.get(nameIndex);
        //获取classDef的TypeIndex 也就是class_idx字段
        int typeIndex = classDef.getTypeIndex();
        //根据typeIndex 获取对应的className
        String className = typeNames.get(typeIndex);
        //native方法 或者 抽象方法
        if(method.getCodeOffset() == 0){
            LogUtils.noisy("method code offset is zero,name =  %s.%s , returnType = %s",
                    TypeUtils.getHumanizeTypeName(className),
                    methodName,
                    TypeUtils.getHumanizeTypeName(returnTypeName));
            return null;
        }
        //创建Instruction对象用来记录dex文件方法的codeItem
        Instruction instruction = new Instruction();
        //16 = registers_size + ins_size + outs_size + tries_size + debug_info_off + insns_size
        int insnsOffset = method.getCodeOffset() + 16;
        //读取method的code_item
        Code code = dex.readCode(method);
        //容错处理
        if(code.getInstructions().length == 0){
            LogUtils.noisy("method has no code,name =  %s.%s , returnType = %s",
                    TypeUtils.getHumanizeTypeName(className),
                    methodName,
                    TypeUtils.getHumanizeTypeName(returnTypeName));
            return null;
        }
        //获取code指令长度 ，这些指令是以 16 位（short 类型）的形式存储的。每条指令在 DEX 文件中通常占用 2 个字节（16 位）
        int insnsCapacity = code.getInstructions().length;
        //insns的容量不足以存储返回语句，跳过它
        byte[] returnByteCodes = getReturnByteCodes(returnTypeName);
        if(insnsCapacity * 2 < returnByteCodes.length){
            LogUtils.noisy("The capacity of insns is not enough to store the return statement. %s.%s() ClassIndex = %d -> %s insnsCapacity = %d byte(s) but returnByteCodes = %d byte(s)",
                    TypeUtils.getHumanizeTypeName(className),
                    methodName,
                    typeIndex,
                    TypeUtils.getHumanizeTypeName(returnTypeName),
                    insnsCapacity * 2,
                    returnByteCodes.length);

            return null;
        }
        //记录insnsOffset(代码指令的偏移量)
        instruction.setOffsetOfDex(insnsOffset);
        //记录 method_idx_diff
        instruction.setMethodIndex(methodIndex);
        //记录代码指令数据大小
        instruction.setInstructionDataSize(insnsCapacity * 2);
        //定义字节数组来存储代码指令数据
        byte[] byteCode = new byte[insnsCapacity * 2];
        //写入 nop 指令
        for (int i = 0; i < insnsCapacity; i++) {
            //classes1_extracted.dat 文件指针移动到到指定偏移位置
            outRandomAccessFile.seek(insnsOffset + (i * 2));
            byteCode[i * 2] = outRandomAccessFile.readByte();
            byteCode[i * 2 + 1] = outRandomAccessFile.readByte();
            outRandomAccessFile.seek(insnsOffset + (i * 2));
            outRandomAccessFile.writeShort(0);
        }
        instruction.setInstructionsData(byteCode);
        outRandomAccessFile.seek(insnsOffset);
        //写入返回指令
        outRandomAccessFile.write(returnByteCodes);
        return instruction;
    }

    /**
     * 根据返回类型获取对应的ByteCodes
     * 详见：<a href="https://source.android.com/docs/core/runtime/dalvik-bytecode?hl=zh-cn">...</a>
     * @param returnTypeName
     * @return
     */
    private static byte[] getReturnByteCodes(String returnTypeName) {
        byte[] returnVoidCodes = {(byte)0x0e , (byte)(0x0)};
        byte[] returnCodes = {(byte)0x12 , (byte)0x0 , (byte) 0x0f , (byte) 0x0};
        byte[] returnWideCodes = {(byte)0x16 , (byte)0x0 , (byte) 0x0 , (byte) 0x0, (byte) 0x10 , (byte) 0x0};
        byte[] returnObjectCodes = {(byte)0x12 , (byte)0x0 , (byte) 0x11 , (byte) 0x0};
        switch (returnTypeName){
            case "V":
                return returnVoidCodes;
            case "B":
            case "C":
            case "F":
            case "I":
            case "S":
            case "Z":
                return returnCodes;
            case "D":
            case "J":
                return returnWideCodes;
            default: {
                return returnObjectCodes;
            }
        }

    }

    /**
     * 替换文件hash
     * @param oldDexFile
     * @param newDexFile
     */
    public static void writeHashes(File oldDexFile,File newDexFile){
        byte[] dexData = IoUtils.readFile(oldDexFile.getAbsolutePath());

        Dex dex = null;
        try {
            dex = new Dex(dexData);
            dex.writeHashes();
            dex.writeTo(newDexFile);
        }
        catch (Exception e){
            e.printStackTrace();
        }
    }
}

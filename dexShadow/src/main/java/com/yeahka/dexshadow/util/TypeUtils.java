package com.yeahka.dexshadow.util;

/**
 * Copyright © Yeahka All Rights Reserved.
 *
 * @author jimmyzhou
 * @date 2024/7/2
 * @desc 类型转换
 */
public class TypeUtils {
    public static String getHumanizeTypeName(String name){
        if(name == null || name.isEmpty()){
            return "";
        }
        switch (name){
            case "V":
                return "void";
            case "I":
                return "int";
            case "D":
                return "double";
            case "F":
                return "float";
            case "S":
                return "short";
            case "Z":
                return "boolean";
            case "J":
                return "long";
            case "B":
                return "byte";
            default:
                if(name.length() >= 2) {
                    //Ljava/lang/System; -> java.lang.System
                    return name.substring(1, name.length() - 1).replaceAll("/", ".");
                }
                else{
                    return name;
                }
        }
    }
}

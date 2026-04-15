# <font style="color:rgb(17, 17, 17);">1.</font>前言
安卓的加固方案一直在进步，我们之前讲解的dex动态加载和dex不落地加载都属于dex整体加固，两者的区别仅在于解密后的dex是否持久化到本地，对于落地壳来说就是需要先解密文件，然后写入到另外一个文件当中，然后再调用DexClassLoader或者其他加载函数来加载解密后的文件，对于不落地壳来说贼是直接在内存中解密内存中加载，对于加固方案来说可能是个技术迭代，但对于脱壳方来说，都可以通过一种方法来解决：DEX DUMP

早先的脱壳技术中主要是通过动态分析调试来进行脱壳，例如通过IDA断点调试然后DUMP内存的方案（现在可以用Frida内存漫游进行DUMP，比如[Frida-DexDump](https://github.com/hluwa/FRIDA-DEXDump)），DUMP内存的方式之一是通过hook某个加载DexFile的函数（不同安卓版本可能不同我们之前也讲过）获得DexFile的结构体的内存起始地址和大小

可以看到这种加固和脱壳的方式的粒度都是Dex级别，而且代码数据总是结构完整的存储在一段内存里面，这是一个致命的弱点，一旦反注入，反调试等方式背破解，这个加固方案就失败了

# 2.指令抽取整体原理
由于第二代加固技术仅仅是在文件级别上对App实施保护，其导致payload在内存中是连续的，可被轻易获取；第三代对此进行改进，将保护级别提升到函数级别。

主要的流程是：加固阶段将原始dex内的函数内容（code item）清除，单独移到一个文件中，运行阶段将函数内容重新恢复到对应的函数体。那我们接下来仔细讲解

# 3.dex文件详解
刚才我们聊到指令抽取在加固阶段是要将原始dex内的函数内容（code item）清除，单独移到一个文件中，那么我们首先就得搞清楚一个非常重要的东西，那就是 如何解析一个dex文件

了解了 Dex 文件以后，对日常开发中遇到一些问题能有更深的理解。如：APK 的瘦身、热修复、插件化、应用加固、Android 逆向工程、64 K 方法数限制。

## 3.1.什么是dex文件
在明白什么是 Dex 文件之前，要先了解一下 JVM，Dalvik 和 ART。JVM 是 JAVA 虚拟机，用来运行 JAVA 字节码程序。Dalvik 是 Google 设计的用于 Android平台的运行时环境，适合移动环境下内存和处理器速度有限的系统。ART 即 Android Runtime，是 Google 为了替换 Dalvik 设计的新 Android 运行时环境，在Android 4.4推出。ART 比 Dalvik 的性能更好。Android 程序一般使用 Java 语言开发，但是 Dalvik 虚拟机并不支持直接执行 JAVA 字节码，所以会对编译生成的 .class 文件进行翻译、重构、解释、压缩等处理，这个处理过程是由 dx 进行处理，处理完成后生成的产物会以 .dex 结尾，称为 Dex 文件。Dex 文件格式是专为 Dalvik 设计的一种压缩格式。所以可以简单的理解为：Dex 文件是很多 .class 文件处理后的产物，最终可以在 Android 运行时环境执行。

## 3.2.dex 文件是怎么生成的
java 代码转化为 dex 文件的流程如图所示，当然真的处理流程不会这么简单，这里只是一个形象的显示：

<!-- 这是一张图片，ocr 内容为： -->
![](https://cdn.nlark.com/yuque/0/2024/png/28000648/1721964525015-3acb703e-cfef-45ab-80bf-fed43c06ae42.png)

现在来通过一个简单的例子实现 java 代码到 dex 文件的转化。

### 3.2.1.从 .java 到 .class
先来创建一个 Hello.java 文件，为了便于分析，这里写一些简单的代码。代码如下：

```java
public class Hello {  
    private String helloString = "helloWorld!!";

    public static void main(String[] args) {
        Hello hello = new Hello();
        hello.fun(hello.helloString);
    }

    public void fun(String a) {
        System.out.println(a);
    }
}
```

在该文件的同级目录下面使用 JDK 的 javac 编译这个 java 文件。

```shell
javac Hello.java  
```

javac 命令执行后会在当前目录生成 Hello.class 文件，Hello.class 文件已经可以直接在 JVM 虚拟机上直接执行。这里使用使用命令执行该文件。

```shell
java Hello  
```

执行后应该会在控制台打印出“helloWorld!!”

这里也可以对 Hello.class 文件执行 javap 命令，进行反汇编。

```shell
javap -c Hello  
```

执行结果如下：

```shell
zhoujin@zhoujindeMacBook-Pro test_dx % javap -c Hello  

Compiled from "Hello.java"
public class Hello {
  public Hello();
    Code:
       0: aload_0
       1: invokespecial #1                  // Method java/lang/Object."<init>":()V
       4: aload_0
       5: ldc           #2                  // String helloWorld!!
       7: putfield      #3                  // Field helloString:Ljava/lang/String;
      10: return

  public static void main(java.lang.String[]);
    Code:
       0: new           #4                  // class Hello
       3: dup
       4: invokespecial #5                  // Method "<init>":()V
       7: astore_1
       8: aload_1
       9: aload_1
      10: getfield      #3                  // Field helloString:Ljava/lang/String;
      13: invokevirtual #6                  // Method fun:(Ljava/lang/String;)V
      16: return

  public void fun(java.lang.String);
    Code:
       0: getstatic     #7                  // Field java/lang/System.out:Ljava/io/PrintStream;
       3: aload_1
       4: invokevirtual #8                  // Method java/io/PrintStream.println:(Ljava/lang/String;)V
       7: return
}
```

其中 Code 之后都是具体的指令，供 JVM 虚拟机执行。指令的具体含义可以参考 JAVA 官方文档。

### 3.2.2.从 .class 到 .dex
dx 处理会使用到一个工具 dx.jar，这个文件位于 SDK 中，具体的目录大致为 你的SDK根目录/build-tools/任意版本 里面(<font style="color:#DF2A3F;">从31.0.0开始dx就被d8代替了</font>)。使用 dx 工具处理上面生成的Hello.class 文件，在 Hello.class 的目录下使用下面的命令：

```plain
dx --dex --output=Hello.dex Hello.class
```

执行完成后，会在当前目录下生成一个 Hello.dex 文件。这个 .dex 文件就可以直接在 Android 运行时环境执行，一般可以通过 PathClassLoader 去加载 dex 文件。现在在当前目录下执行 dexdump 命名来反编译：

```shell
dexdump -d Hello.dex  
```

执行结果如下：

```shell
Processing 'Hello.dex'...
Opened 'Hello.dex', DEX version '035'
Class #0            -
  Class descriptor  : 'LHello;'
  Access flags      : 0x0001 (PUBLIC)
  Superclass        : 'Ljava/lang/Object;'
  Interfaces        -
  Static fields     -
  Instance fields   -
    #0              : (in LHello;)
      name          : 'helloString'
      type          : 'Ljava/lang/String;'
      access        : 0x0002 (PRIVATE)
  Direct methods    -
    #0              : (in LHello;)
      name          : '<init>'
      type          : '()V'
      access        : 0x10001 (PUBLIC CONSTRUCTOR)
      code          -
      registers     : 2
      ins           : 1
      outs          : 1
      insns size    : 8 16-bit code units
000148:                                        |[000148] Hello.<init>:()V
000158: 7010 0400 0100                         |0000: invoke-direct {v1}, Ljava/lang/Object;.<init>:()V // method@0004
00015e: 1a00 0c00                              |0003: const-string v0, "helloWorld!!" // string@000c
000162: 5b10 0000                              |0005: iput-object v0, v1, LHello;.helloString:Ljava/lang/String; // field@0000
000166: 0e00                                   |0007: return-void
      catches       : (none)
      positions     : 
        0x0000 line=1
        0x0003 line=2
      locals        : 
        0x0000 - 0x0008 reg=1 this LHello; 

    #1              : (in LHello;)
      name          : 'main'
      type          : '([Ljava/lang/String;)V'
      access        : 0x0009 (PUBLIC STATIC)
      code          -
      registers     : 3
      ins           : 1
      outs          : 2
      insns size    : 11 16-bit code units
000168:                                        |[000168] Hello.main:([Ljava/lang/String;)V
000178: 2200 0000                              |0000: new-instance v0, LHello; // type@0000
00017c: 7010 0000 0000                         |0002: invoke-direct {v0}, LHello;.<init>:()V // method@0000
000182: 5401 0000                              |0005: iget-object v1, v0, LHello;.helloString:Ljava/lang/String; // field@0000
000186: 6e20 0100 1000                         |0007: invoke-virtual {v0, v1}, LHello;.fun:(Ljava/lang/String;)V // method@0001
00018c: 0e00                                   |000a: return-void
      catches       : (none)
      positions     : 
        0x0000 line=5
        0x0005 line=6
        0x000a line=7
      locals        : 
        0x0000 - 0x000b reg=2 (null) [Ljava/lang/String; 

  Virtual methods   -
    #0              : (in LHello;)
      name          : 'fun'
      type          : '(Ljava/lang/String;)V'
      access        : 0x0001 (PUBLIC)
      code          -
      registers     : 3
      ins           : 2
      outs          : 2
      insns size    : 6 16-bit code units
000190:                                        |[000190] Hello.fun:(Ljava/lang/String;)V
0001a0: 6200 0100                              |0000: sget-object v0, Ljava/lang/System;.out:Ljava/io/PrintStream; // field@0001
0001a4: 6e20 0300 2000                         |0002: invoke-virtual {v0, v2}, Ljava/io/PrintStream;.println:(Ljava/lang/String;)V // method@0003
0001aa: 0e00                                   |0005: return-void
      catches       : (none)
      positions     : 
        0x0000 line=10
        0x0005 line=11
      locals        : 
        0x0000 - 0x0006 reg=1 this LHello; 
        0x0000 - 0x0006 reg=2 (null) Ljava/lang/String; 

  source_file_idx   : 1 (Hello.java)
```

我们详细解释下这个输出

## 3.3.dex文件指令详解
### 3.3.1.类定义
```shell
Class #0            -
  Class descriptor  : 'LHello;'
  Access flags      : 0x0001 (PUBLIC)
  Superclass        : 'Ljava/lang/Object;'
  Interfaces        -
  Static fields     -
  Instance fields   -
    #0              : (in LHello;)
      name          : 'helloString'
      type          : 'Ljava/lang/String;'
      access        : 0x0002 (PRIVATE)

```

+ **Class #0**: 类的编号是 0。
+ **Class descriptor : 'LHello;'**: 类描述符是 `LHello;`，表示类名是 `Hello`。
+ **Access flags : 0x0001 (PUBLIC)**: 访问标志是 `0x0001`，表示这个类是 `public`。
+ **Superclass : 'Ljava/lang/Object;'**: 该类继承自 `java.lang.Object`。
+ **Interfaces**: 这个类没有实现任何接口。
+ **Static fields**: 这个类没有静态字段。
+ **Instance fields**:
+ **#0 : (in LHello;) name : 'helloString' type : 'Ljava/lang/String;' access : 0x0002 (PRIVATE)**:
    - **name**: 字段名是 `helloString`。
    - **type**: 字段类型是 `java.lang.String`。
    - **access**: 访问标志是 `0x0002`，表示这个字段是 `private`。

### 3.3.2.虚方法（Virtual Methods）
`Virtual Methods` 是那些需要通过虚方法表（vtable）调用的方法。这些方法包括：

1. **实例方法（Instance Methods）****：这些方法在类的实例上调用，且不是静态的。**
2. **被重写的方法（Overridden Methods）****：这些方法在子类中可以被重写。**

**当调用 **`**Virtual Methods**`** 时，虚拟机会通过 vtable 来确定实际调用的方法实现。这样可以支持多态，即根据对象的实际类型来调用对应的重写方法。**

```plain
  Virtual methods   -
    #0              : (in LHello;)
      name          : 'fun'
      type          : '(Ljava/lang/String;)V'
      access        : 0x0001 (PUBLIC)
      code          -
      registers     : 3
      ins           : 2
      outs          : 2
      insns size    : 6 16-bit code units

```

**#0 : (in LHello;) name : 'fun' type : '(Ljava/lang/String;)V' access : 0x0001 (PUBLIC)**:

+ **name**: 方法名是 `fun`。
+ **type**: 方法签名是 `(Ljava/lang/String;)V`，表示接受一个 `String` 作为参数且无返回值。
+ **access**: 访问标志是 `0x0001`，表示这个方法是 `public`。
+ **registers**: 使用了 3 个寄存器。<font style="color:#DF2A3F;">些寄存器用来存储方法调用中使用的变量和参数</font>

<font style="color:#DF2A3F;">1.</font>`<font style="color:#DF2A3F;">v0</font>`<font style="color:#DF2A3F;">：用于存储 </font>`<font style="color:#DF2A3F;">System.out</font>`<font style="color:#DF2A3F;">。</font>

<font style="color:#DF2A3F;">2.</font>`<font style="color:#DF2A3F;">v1</font>`<font style="color:#DF2A3F;">：用于存储 </font>`<font style="color:#DF2A3F;">this</font>`<font style="color:#DF2A3F;"> 引用。</font>

<font style="color:#DF2A3F;">3.</font>`<font style="color:#DF2A3F;">v2</font>`<font style="color:#DF2A3F;">：用于存储方法参数 </font>`<font style="color:#DF2A3F;">a</font>`<font style="color:#DF2A3F;">。</font>

+ **ins**: 输入操作数为 2。<font style="color:#DF2A3F;">表示方法调用时传入了 2 个参数</font>

<font style="color:#DF2A3F;">      1.方法所属的对象引用 (</font>`<font style="color:#DF2A3F;">this</font>`<font style="color:#DF2A3F;">)。</font>

<font style="color:#DF2A3F;">      2.方法的实际参数 (</font>`<font style="color:#DF2A3F;">a</font>`<font style="color:#DF2A3F;">)，即 </font>`<font style="color:#DF2A3F;">String</font>`<font style="color:#DF2A3F;"> 类型的参数。</font>

+ **outs**: 输出操作数为 2。<font style="color:#DF2A3F;">表示在调用其他方法时传递了 2 个参数</font>：

1.`<font style="color:#DF2A3F;">System.out</font>`<font style="color:#DF2A3F;">，即 </font>`<font style="color:#DF2A3F;">PrintStream</font>`<font style="color:#DF2A3F;"> 对象。</font>

2.`<font style="color:#DF2A3F;">a</font>`<font style="color:#DF2A3F;">，即传递给 </font>`<font style="color:#DF2A3F;">println</font>`<font style="color:#DF2A3F;"> 方法的 </font>`<font style="color:#DF2A3F;">String</font>`<font style="color:#DF2A3F;"> 参数。</font>

+ **insns size**: 指令大小为 6 个 16 位代码单元。

**fun 方法指令**

```plain
000190:                                        |[000190] Hello.fun:(Ljava/lang/String;)V
0001a0: 6200 0100                              |0000: sget-object v0, Ljava/lang/System;.out:Ljava/io/PrintStream; // field@0001
0001a4: 6e20 0300 2000                         |0002: invoke-virtual {v0, v2}, Ljava/io/PrintStream;.println:(Ljava/lang/String;)V // method@0003
0001aa: 0e00                                   |0005: return-void

```

+ **000190**: `fun` 方法的起始位置。
+ **0001a0: 6200 0100**:
    - **6200**: `sget-object` 指令的操作码。
    - **0100**: 字段索引 `0001`，表示 `Ljava/lang/System;.out:Ljava/io/PrintStream;`。
    - **sget-object v0, Ljava/lang/System;.out****/io/PrintStream;**: 从 `System` 类中获取静态字段 `out` 的值，并存储在寄存器 `v0` 中。
+ **0001a4: 6e20 0300 2000**:
    - **6e20**: `invoke-virtual` 指令的操作码。
    - **0300**: 方法索引 `0003`，表示 `Ljava/io/PrintStream;.println:(Ljava/lang/String;)V`。
    - **2000**: 使用寄存器 `v0` 和 `v2`。
    - **invoke-virtual {v0, v2}, Ljava/io/PrintStream;.println:(Ljava/lang/String;)V**: 调用 `PrintStream` 类的 `println` 方法，传入 `v2`（即 `String`）作为参数。
+ **0001aa: 0e00**:
    - **0e00**: `return-void` 指令的操作码，表示返回 `void`。

**额外信息**

+ **catches**: (none)，表示没有异常捕获。
+ **positions**: 显示源码中的行号对应的字节码位置。
+ **locals**: 本地变量信息。

### 3.3.3.直接方法（Direct Methods）
`Direct Methods` 是指那些不需要通过虚方法表（virtual method table，vtable）调用的方法。这些方法包括：

1. **构造函数（Constructors）**：如 `<init>` 和 `<clinit>` 方法。
2. **私有方法（Private Methods）**：这些方法只能在类的内部调用。
3. **静态方法（Static Methods）**：这些方法与类关联，而不是与类的实例关联。

因为 `Direct Methods` 是直接通过方法 ID 调用的，所以它们不需要通过 vtable 进行间接调用。

```shell
  Direct methods    -
    #0              : (in LHello;)
      name          : '<init>'
      type          : '()V'
      access        : 0x10001 (PUBLIC CONSTRUCTOR)
      code          -
      registers     : 2
      ins           : 1
      outs          : 1
      insns size    : 8 16-bit code units

```

**#0 : (in LHello;) name : '<init>' type : '()V' access : 0x10001 (PUBLIC CONSTRUCTOR)**:

+ **name**: 方法名是 `<init>`，表示构造函数。
+ **type**: 方法签名是 `()V`，表示无参数且无返回值。
+ **access**: 访问标志是 `0x10001`，表示这个方法是 `public` 构造函数。
+ **registers**: 使用了 2 个寄存器。<font style="color:#DF2A3F;">寄存器数量 (</font>`<font style="color:#DF2A3F;">registers</font>`<font style="color:#DF2A3F;">) 包括输入操作数 (</font>`<font style="color:#DF2A3F;">ins</font>`<font style="color:#DF2A3F;">)、输出操作数 (</font>`<font style="color:#DF2A3F;">outs</font>`<font style="color:#DF2A3F;">) 以及方法中使用的所有局部变量和临时变量的总和</font>
+ **ins**: 该方法输入参数数量 1，<font style="color:#DF2A3F;">尽管构造方法没有显式参数，但 </font>`<font style="color:#DF2A3F;">this</font>`<font style="color:#DF2A3F;"> 引用仍然需要作为输入操作数传递给构造方法，因此 </font>`<font style="color:#DF2A3F;">ins</font>`<font style="color:#DF2A3F;"> 的值为 1</font>。
+ **outs**: 该方法输出参数数量 1。
+ **insns size**: 指令大小为 8 个 16 位代码单元。

**构造函数指令**

```plain
000148:                                        |[000148] Hello.<init>:()V
000158: 7010 0400 0100                         |0000: invoke-direct {v1}, Ljava/lang/Object;.<init>:()V // method@0004
00015e: 1a00 0c00                              |0003: const-string v0, "helloWorld!!" // string@000c
000162: 5b10 0000                              |0005: iput-object v0, v1, LHello;.helloString:Ljava/lang/String; // field@0000
000166: 0e00                                   |0007: return-void
```

+ **000148**: 构造函数 `Hello.<init>:()V` 的起始位置, 这个偏移地址表示这条指令在 DEX 文件代码段中的起始位置是 0x0148
+ **000158: 7010 0400 0100**:
+ **7010**: `invoke-direct` 指令的操作码。
+ **0400**: 方法索引 `0004`，表示 `java.lang.Object.<init>:()V`。
+ **0100**: 使用寄存器 `v1`。
+ **invoke-direct {v1}, Ljava/lang/Object;.<init>:()V**: 调用 `Object` 的构造函数。
+ **00015e: 1a00 0c00**:
+ **1a00**: `const-string` 指令的操作码。
+ **0c00**: 字符串索引 `000c`，表示字符串 `"helloWorld!!"`。
+ **const-string v0, "helloWorld!!"**: 将字符串 `"helloWorld!!"` 加载到寄存器 `v0`。
+ **000162: 5b10 0000**:
+ **5b10**: `iput-object` 指令的操作码。
+ **0000**: 字段索引 `0000`，表示 `LHello;.helloString:Ljava/lang/String;`。
+ **iput-object v0, v1, LHello;.helloString****/lang/String;**: 将寄存器 `v0` 中的对象存入 `v1`（即 `this`）的 `helloString` 字段。
+ **000166: 0e00**:
+ **0e00**: `return-void` 指令的操作码，表示返回 `void`。

### 3.3.4.静态方法（Static Methods）
```plain
    #1              : (in LHello;)
      name          : 'main'
      type          : '([Ljava/lang/String;)V'
      access        : 0x0009 (PUBLIC STATIC)
      code          -
      registers     : 3
      ins           : 1
      outs          : 2
      insns size    : 11 16-bit code units

```

**#1 : (in LHello;) name : 'main' type : '([Ljava/lang/String;)V' access : 0x0009 (PUBLIC STATIC)**:

+ **name**: 方法名是 `main`。
+ **type**: 方法签名是 `([Ljava/lang/String;)V`，表示接受一个 `String` 数组作为参数且无返回值。
+ **access**: 访问标志是 `0x0009`，表示这个方法是 `public static`。
+ **registers**: 使用了 3 个寄存器。
+ **ins**: 输入操作数为 1。
+ **outs**: 输出操作数为 2。
+ **insns size**: 指令大小为 11 个 16 位代码单元。

main 方法指令：

```plain
000168:                                        |[000168] Hello.main:([Ljava/lang/String;)V
000178: 2200 0000                              |0000: new-instance v0, LHello; // type@0000
00017c: 7010 0000 0000                         |0002: invoke-direct {v0}, LHello;.<init>:()V // method@0000
000182: 5401 0000                              |0005: iget-object v1, v0, LHello;.helloString:Ljava/lang/String; // field@0000
000186: 6e20 0100 1000                         |0007: invoke-virtual {v0, v1}, LHello;.fun:(Ljava/lang/String;)V // method@0001
00018c: 0e00                                   |000a: return-void

```

+ **000168**: `main` 方法的起始位置。
+ **000178: 2200 0000**:
+ **2200**: `new-instance` 指令的操作码。
+ **0000**: 类型索引 `0000`，表示 `LHello;`。
+ **new-instance v0, LHello;**: 创建一个新的 `Hello` 类实例，并将引用存储在寄存器 `v0` 中。
+ **00017c: 7010 0000 0000**:
+ **7010**: `invoke-direct` 指令的操作码。
+ **0000**: 方法索引 `0000`，表示 `LHello;.<init>:()V`。
+ **invoke-direct {v0}, LHello;.<init>:()V**: 调用 `Hello` 类的构造函数。
+ **000182: 5401 0000**:
+ **5401**: `iget-object` 指令的操作码。
+ **0000**: 字段索引 `0000`，表示 `LHello;.helloString:Ljava/lang/String;`。
+ **iget-object v1, v0, LHello;.helloString****/lang/String;**: 从 `v0` 中的 `Hello` 实例获取 `helloString` 字段的值，并存储在寄存器 `v1` 中。
+ **000186: 6e20 0100 1000**:
+ **6e20**: `invoke-virtual` 指令的操作码。
+ **0100**: 方法索引 `0001`，表示 `LHello;.fun:(Ljava/lang/String;)V`。
+ **1000**: 使用寄存器 `v0` 和 `v1`。
+ **invoke-virtual {v0, v1}, LHello;.fun:(Ljava/lang/String;)V**: 调用 `Hello` 类的 `fun` 方法，传入 `v1`（即 `helloString`）作为参数。
+ **00018c: 0e00**:
+ **0e00**: `return-void` 指令的操作码，表示返回 `void`。

## 3.4.<font style="color:rgb(31, 32, 38);background-color:rgb(250, 250, 250);">dex文件的具体格式</font>
现在来分析一下 dex 文件的具体格式，就像 MP3，MP4，JPG，PNG 文件一样，dex 文件也有它自己的格式，只有遵守了这些格式，才能被 Android 运行时环境正确识别。

dex 文件整体布局如下图所示：

                                                                  <!-- 这是一张图片，ocr 内容为： -->
![](https://cdn.nlark.com/yuque/0/2024/png/28000648/1722222704101-9acf7af7-f44d-4c7d-97e7-7c68e3fdeeb0.png)

这些区域的数据互相关联，互相引用。由于篇幅原因，这里只是显示部分区域的关联，完整的请去官网自行查看相关数据整理。下图中的各字段都在后面的各区域的详细介绍中有具体介绍。

                        <!-- 这是一张图片，ocr 内容为： -->
![](https://cdn.nlark.com/yuque/0/2024/png/28000648/1722222840789-394a5389-d266-42c0-ae6f-16822467480a.png) 

当我们使用010Editor打开dex文件：

<!-- 这是一张图片，ocr 内容为： -->
![](https://cdn.nlark.com/yuque/0/2024/png/28000648/1722253486062-b209b810-fcd5-49bc-bc0a-788acda3a899.png)

1. 行标头（左侧的红色框）

行标头表示文件中每一行数据在整个文件中的起始偏移量（以十六进制表示）。每一行的偏移量相差 0x10（16 字节）。行标头帮助你快速定位文件中的具体位置。

例如：

+ `0000h` 表示这一行的数据从文件的第 0 字节开始。
+ `0010h` 表示这一行的数据从文件的第 16 字节开始。
+ `0020h` 表示这一行的数据从文件的第 32 字节开始。
+ 依此类推。

2. 列标头（顶部的红色框）

列标头表示行中的每个字节在这一行中的位置，范围是 0 到 F（0 到 15，以十六进制表示）。这些标头帮助你在行内准确定位某个字节。

例如，假设你在 `0010h` 行，标头为 `5` 的列查找一个字节：

+ 你查找的位置是第 `0x15`（21）个字节。

如何使用这些标头定位文件中的数据

结合行标头和列标头，你可以在文件中精确定位到每个字节的位置。

例如，如果你想查找文件中偏移量为 `0x24` 位置的字节：

1. 找到行标头 `0020h`（表示文件的第 32 字节开始）。
2. 在这一行中找到列标头 `4`，表示这一行的第 5 个字节（0 开始计数）。

因此，偏移量 `0x24` 的字节位于文件的第 `0020h` 行和列标头 `4` 的位置。

下面将分别对文件头、索引区、类定义区域进行简单的介绍。其它区域可以去 Android 官网了解。

### 3.4.1.文件头(dex_header)
文件头区域决定了该怎样来读取这个文件。具体的格式如下表(在文件中排列的顺序就是下面表格中的顺序)：

<!-- 这是一张图片，ocr 内容为： -->
![](https://cdn.nlark.com/yuque/0/2024/png/28000648/1722253069245-e521d481-0c2d-4902-8a0e-cf1f3415ac8d.png)

+ **magic**: 魔法值，用于标识文件格式。通常是字符串 "dex\n035\0"。
+ **checksum**: 文件其余部分的Adler32校验和，用于验证文件完整性。
+ **signature**: 文件其余部分的SHA-1签名，用于验证文件完整性。
+ **file_size**: 文件的总大小（字节）。
+ **header_size**: 头部的大小（字节），通常是112字节。
+ **endian_tag**: 表示文件的字节序（大端或小端）。
+ **link_size**: 链接部分的大小。通常为0。
+ **link_off**: 链接部分的文件偏移量。通常为0。
+ **map_off**: 映射列表的文件偏移量。
+ **string_ids_size**: 字符串ID列表中的字符串数量。
+ **string_ids_off**: 字符串ID列表的文件偏移量。
+ **type_ids_size**: 类型ID列表中的类型数量。
+ **type_ids_off**: 类型ID列表的文件偏移量。
+ **proto_ids_size**: 方法原型ID列表中的项目数量。
+ **proto_ids_off**: 方法原型ID列表的文件偏移量。
+ **field_ids_size**: 字段ID列表中的项目数量。
+ **field_ids_off**: 字段ID列表的文件偏移量。
+ **method_ids_size**: 方法ID列表中的项目数量。
+ **method_ids_off**: 方法ID列表的文件偏移量。
+ **class_defs_size**: 类定义列表中的项目数量。
+ **class_defs_off**: 类定义列表的文件偏移量。
+ **data_size**: 数据区的大小（字节）。
+ **data_off**: 数据区的文件偏移量。

### 3.4.2.字符串ID列表(dex_string_ids)
这些字段和偏移量定义了字符串ID列表的结构和内容，以及每个字符串在文件中的实际存储位置。字符串数据使用MUTF-8格式进行编码

<!-- 这是一张图片，ocr 内容为： -->
![](https://cdn.nlark.com/yuque/0/2024/png/28000648/1722254084165-be4ec4bf-e3e0-4a75-bd06-8dac0f54e947.png)

下面是对这些字段的解释：

```plain
struct string_id_list {
    struct string_id_item string_id[16];
};
```

解释如下：

1. **string_id_list**: 字符串ID列表结构。
2. **string_id_item**: 字符串ID项结构。每个项都有以下字段：
    - **string_data_off**: 字符串数据的文件偏移量。

```plain
struct string_id_item {
    uint string_data_off;
};
```

解释如下：

1. **string_data_off**: 字符串数据的文件偏移量。用于指向实际字符串数据。

```plain
struct string_item {
    struct uleb128 utf16_size;
    ubyte val;
    string data[7];
};
```

解释如下：

1. **utf16_size**: 字符串的UTF-16编码单元大小。
2. **val**: 用于表示字符串的字节。
3. **data**: MUTF-8格式的字符串数据。

<font style="color:#DF2A3F;">DEX文件中包含的字符串不仅包括源代码中明确声明的字符串，还包括编译过程中生成的其他字符串，例如类名、方法名、类型描述符等。这样做是为了支持虚拟机运行所需的各种信息。</font>

<font style="color:#DF2A3F;">具体来说，DEX文件中包含的字符串主要包括以下几类：</font>

1. **<font style="color:#DF2A3F;">类名</font>**<font style="color:#DF2A3F;">：例如 </font>`<font style="color:#DF2A3F;">Hello</font>`<font style="color:#DF2A3F;">、</font>`<font style="color:#DF2A3F;">Ljava/lang/Object</font>`<font style="color:#DF2A3F;"> 等。</font>
2. **<font style="color:#DF2A3F;">方法名</font>**<font style="color:#DF2A3F;">：例如 </font>`<font style="color:#DF2A3F;"><init></font>`<font style="color:#DF2A3F;">（构造方法）、</font>`<font style="color:#DF2A3F;">main</font>`<font style="color:#DF2A3F;">、</font>`<font style="color:#DF2A3F;">fun</font>`<font style="color:#DF2A3F;"> 等。</font>
3. **<font style="color:#DF2A3F;">字段名</font>**<font style="color:#DF2A3F;">：例如 </font>`<font style="color:#DF2A3F;">helloString</font>`<font style="color:#DF2A3F;">。</font>
4. **<font style="color:#DF2A3F;">类型符</font>**<font style="color:#DF2A3F;">：例如 </font>`<font style="color:#DF2A3F;">Ljava/lang/String;</font>`<font style="color:#DF2A3F;">、</font>`<font style="color:#DF2A3F;">[Ljava/lang/String;</font>`<font style="color:#DF2A3F;"> 等。</font>
5. **<font style="color:#DF2A3F;">常量字符串</font>**<font style="color:#DF2A3F;">：例如 </font>`<font style="color:#DF2A3F;">"helloWorld!!"</font>`<font style="color:#DF2A3F;">。</font>

### 3.4.3.类型ID列表（type_id_list）
<!-- 这是一张图片，ocr 内容为： -->
![](https://cdn.nlark.com/yuque/0/2024/png/28000648/1722255542334-69b92c85-3b73-4d40-b811-0b1acd9ef87b.png)

图片中的内容解释如下：

+ `dex_type_ids`：类型ID列表，包含7个类型ID项。
+ `type_id[0]`：
    - `descriptor_idx`：索引值 `0x2`，对应字符串ID `LHello;`。
+ `type_id[1]`：
    - `descriptor_idx`：索引值 `0x3`，对应字符串ID `Ljava/io/PrintStream;`。
+ `type_id[2]`：
    - `descriptor_idx`：索引值 `0x4`，对应字符串ID `Ljava/lang/Object;`。
+ `type_id[3]`：
    - `descriptor_idx`：索引值 `0x5`，对应字符串ID `Ljava/lang/String;`。
+ `type_id[4]`：
    - `descriptor_idx`：索引值 `0x6`，对应字符串ID `Ljava/lang/System;`。
+ `type_id[5]`：
    - `descriptor_idx`：索引值 `0x7`，对应字符串ID `V`（void类型）。
+ `type_id[6]`：
    - `descriptor_idx`：索引值 `0x9`，对应字符串ID `[Ljava/lang/String;`（字符串数组类型）。

这些类型ID项描述了DEX文件中使用的所有类型，并通过描述符索引指向对应的字符串ID，标识了类型的具体信息。<font style="color:#DF2A3F;">总结来说，每个类型ID项中的 </font>`<font style="color:#DF2A3F;">descriptor_idx</font>`<font style="color:#DF2A3F;"> 字段指向字符串I指向字符串ID列表</font>`<font style="color:#DF2A3F;">string_ids</font>`<font style="color:#DF2A3F;"> 的索引</font><font style="color:#DF2A3F;">，索引对应的字符串描述了一个类型。</font>

### 3.4.4.方法原型ID列表(dex_proto_ids)
<!-- 这是一张图片，ocr 内容为： -->
![](https://cdn.nlark.com/yuque/0/2024/png/28000648/1722307816247-ec155b92-f9fd-497c-9d1c-5dff09e2b2e4.png)

这个列表包含了方法的原型，每个原型描述了方法的返回类型和参数类型。

每个 `proto_id_item` 包含以下字段：

+ `shorty_idx`：<font style="color:#DF2A3F;"></font>`<font style="color:#DF2A3F;">string_ids</font>`<font style="color:#DF2A3F;"> 列表的索引</font>，该字符串为方法的短描述符（short-form descriptor）。
+ `return_type_idx`：<font style="color:#DF2A3F;"></font>`<font style="color:#DF2A3F;">type_ids</font>`<font style="color:#DF2A3F;"> 列表的索引</font>，该类型为方法的返回类型。
+ `parameters_off`：指向参数类型列表的偏移，如果该方法没有参数，这个字段为0。

**方法 **`**void <init>())**`

+ `proto_id[0]`：
    - `shorty_idx` = `0x7`，对应字符串 `V`，表示返回类型为 `void`，且无参数。
    - `return_type_idx` = `0x5`，对应 `V`，即 `void`。
    - `parameters_off` = `0`，表示没有参数。

**方法 **`**void fun(String a)**`

+ `proto_id[1]`：
    - `shorty_idx` = `0x8`，对应字符串 `VL`，表示返回类型为 `void`，参数类型为 `Ljava/lang/String;`。
    - `return_type_idx` = `0x5`，对应 `V`，即 `void`。
    - `parameters_off` = `428`，指向参数类型列表，包含一个参数 `Ljava/lang/String;`。

**方法 **`**void main(String[] args)**`

+ `proto_id[2]`：
    - `shorty_idx` = `0x8`，对应字符串 `VL`，表示返回类型为 `void`，参数类型为 `Ljava/lang/String[];`。
    - `return_type_idx` = `0x5`，对应 `V`，即 `void`。
    - `parameters_off` = `436`，指向参数类型列表，包含一个参数 `Ljava/lang/String[];`。

### 3.4.5.字段ID列表(dex_file_ids)
<!-- 这是一张图片，ocr 内容为： -->
![](https://cdn.nlark.com/yuque/0/2024/png/28000648/1722320281895-0caed111-9078-440c-8869-aabf51c278bc.png)

在 DEX 文件中，`field_id` 结构用于描述类中的字段信息，包括字段所在类的类型、字段本身的类型以及字段的名称。通过这些信息，DEX 文件能够准确描述 Java 类中的字段布局和类型信息。

`field_id` 列表包含了所有字段的描述信息。每个 `field_id_item` 结构包含三个索引：

1. `class_idx`：字段所在类的类型索引，<font style="color:#DF2A3F;"></font><font style="color:#DF2A3F;"> </font>`<font style="color:#DF2A3F;">type_ids</font>`<font style="color:#DF2A3F;"> 列表的索引</font>
2. `type_idx`：字段的类型索引，<font style="color:#DF2A3F;"></font>`<font style="color:#DF2A3F;">type_ids</font>`<font style="color:#DF2A3F;"> 列表的索引</font>
3. `name_idx`：字段名称的字符串索引，`<font style="color:#DF2A3F;">string_ids</font>`<font style="color:#DF2A3F;"> 列表的索引</font>

**字段 1：**`**Hello.helloString**`

+ `class_idx = 0x0`：指向 `Hello` 类的类型索引（在 `type_id` 列表中索引为 0）
+ `type_idx = 0x3`：指向 `java.lang.String` 类型的类型索引（在 `type_id` 列表中索引为 3）
+ `name_idx = 0xB`：指向 `helloString` 的字符串索引（在 `string_id` 列表中索引为 11）

**字段 2：**`**java.lang.System.out**`

+ `class_idx = 0x4`：指向 `java.lang.System` 类的类型索引（在 `type_id` 列表中索引为 4）
+ `type_idx = 0x1`：指向 `java.io.PrintStream` 类型的类型索引（在 `type_id` 列表中索引为 1）
+ `name_idx = 0xE`：指向 `out` 的字符串索引（在 `string_id` 列表中索引为 14）

### 3.4.6.方法ID列表(dex_method_ids)
<!-- 这是一张图片，ocr 内容为： -->
![](https://cdn.nlark.com/yuque/0/2024/png/28000648/1722321255482-511fac53-1e7b-4219-8e6c-82b16501d2f6.png)

`method_id` 表中记录了所有需要在执行时调用的方法的信息，包括方法所属的类、方法的原型以及方法名。这些信息在 DEX 文件中用于解析和执行方法调用

`method_id` 表中包含了 5 个方法，每个方法都对应一个 `method_id_item` 结构体。每个 `method_id_item` 结构体有三个字段：

`class_idx`（方法所属类的索引，`<font style="color:#DF2A3F;">type_ids</font>`<font style="color:#DF2A3F;"> 列表的索引</font>）

`proto_idx`（方法原型的索引，`<font style="color:#DF2A3F;">proto_ids</font>`<font style="color:#DF2A3F;"> 列表的索引</font>）

`name_idx`（方法名的索引，`<font style="color:#DF2A3F;">string_ids</font>`<font style="color:#DF2A3F;"> 列表的索引</font>）

以下是每个 `method_id_item` 的详细解释：

**method_id[0]**

+ `ushort class_idx = 0x0` (Hello)
+ `ushort proto_idx = 0x0` (void ())
+ `uint name_idx = 0x2` ("<init>")

表示 `Hello` 类的默认构造方法 `<init>`，无参数，返回类型为 `void`。

**method_id[1]**

+ `ushort class_idx = 0x0` (Hello)
+ `ushort proto_idx = 0x1` (void (java.lang.String))
+ `uint name_idx = 0xA` ("fun")

表示 `Hello` 类的 `fun` 方法，参数为 `java.lang.String`，返回类型为 `void`。

**method_id[2]**

+ `ushort class_idx = 0x0` (Hello)
+ `ushort proto_idx = 0x2` (void (java.lang.String[]))
+ `uint name_idx = 0xD` ("main")

表示 `Hello` 类的 `main` 方法，参数为 `java.lang.String[]`，返回类型为 `void`。

**method_id[3]**

+ `ushort class_idx = 0x1` (java.io.PrintStream)
+ `ushort proto_idx = 0x1` (void (java.lang.String))
+ `uint name_idx = 0xF` ("println")

表示 `java.io.PrintStream` 类的 `println` 方法，参数为 `java.lang.String`，返回类型为 `void`。这是因为 `System.out.println(a)` 中使用到了 `println` 方法。

**method_id[4]**

+ `ushort class_idx = 0x2` (java.lang.Object)
+ `ushort proto_idx = 0x0` (void ())
+ `uint name_idx = 0x2` ("<init>")

表示 `java.lang.Object` 类的默认构造方法 `<init>`，无参数，返回类型为 `void`。这是因为所有类都继承自 `java.lang.Object` 类。

### <font style="color:#DF2A3F;">3.4.7.类定义列表(dex_class_defs)</font>
<!-- 这是一张图片，ocr 内容为： -->
![](https://cdn.nlark.com/yuque/0/2024/png/28000648/1722321852341-05da43d7-1d48-4417-8cff-4c24bb60f8ab.png)

`class_def` 表中记录了 类的定义信息，包括类的访问权限、父类、源文件、字段和方法信息

+ `uint class_idx = 128h` (0x0) 表示 `Hello` 类在类型 ID 列表中的索引。
+ `enum ACCESS_FLAGS access_flags = 12Ch` (0x1) 表示类的访问标志，这里是 `ACC_PUBLIC`，表示该类是公共类。
+ `uint superclass_idx = 130h` (0x2) 表示父类 `java.lang.Object` 在类型 ID 列表中的索引。
+ `uint interfaces_off = 134h` 表示接口列表的文件偏移量，这里为 0，表示该类没有实现任何接口。
+ `uint source_file_idx = 138h` (0x1) 表示定义该类的源文件名称的字符串 ID 索引，这里是 `Hello.java`。
+ `uint annotations_off = 13Ch` 表示注解的文件偏移量，这里为 0，表示该类没有注解。
+ `uint class_data_off = 140h` (0x28F) 表示类数据的文件偏移量。类数据包含类的字段和方法信息。
+ `uint static_values_off = 144h` 表示静态字段初始值的文件偏移量，这里为 0，表示该类没有静态字段。

**<font style="color:#DF2A3F;">struct class_data_item class_data(非常重要！！！！！！！！)</font>**

**<font style="color:#DF2A3F;"> class_data类数据如下</font>**

<!-- 这是一张图片，ocr 内容为： -->
![](https://cdn.nlark.com/yuque/0/2024/png/28000648/1722323165314-9cda35ca-f3d4-40a8-85ae-eccebf18b07d.png)

+ `static_fields_size = 0x0`
+ 静态字段数量，这里为 0，表示没有静态字段。
+ `instance_fields_size = 0x1`
+ 实例字段数量，这里为 1，表示有一个实例字段 `helloString`。
+ `direct_methods_size = 0x2`
+ 直接方法数量，这里为 2，表示有两个直接方法（构造方法和 `main` 方法）。
+ `virtual_methods_size = 0x1`
+ 虚拟方法数量，这里为 1，表示有一个虚拟方法 `fun`。

**struct encoded_field_list instance_fields**

+ `struct encoded_field field`
    - 实例字段的编码序列，这里有一个字段 `private java.lang.String Hello.helloString`。
    - `field_idx_diff = 0x0`
        * 字段索引差值，这里为 0，表示这是第一个字段。
    - `access_flags = (0x2) ACC_PRIVATE`
        * 访问标志，这里为 `ACC_PRIVATE`，表示字段是私有的。

**struct encoded_method_list direct_methods**

+ 直接方法的编码序列，这里有两个方法。
    - `struct encoded_method method`
        * 方法的编码信息。
        * `method_idx_diff = 0x1`
            + 方法索引差值，这里为 1，表示相对于上一个方法的索引增量。
        * `access_flags = (0x1) ACC_PUBLIC`
            + 访问标志，这里为 `ACC_PUBLIC`，表示方法是公共的。
        * `code_off = 0x190`
            + 方法代码段的文件偏移量。

**struct encoded_method_list virtual_methods**

+ 虚拟方法的编码序列，这里有一个方法 `public void Hello.fun(java.lang.String)`。
    - `struct encoded_method method`
        * 方法的编码信息。
        * `method_idx_diff = 0x1`
            + 方法索引差值，这里为 1，表示相对于上一个方法的索引增量。
        * `access_flags = (0x1) ACC_PUBLIC`
            + 访问标志，这里为 `ACC_PUBLIC`，表示方法是公共的。
        * `code_off = 0x190`
            + 方法代码段的文件偏移量。

**<font style="color:#DF2A3F;">struct code_item code(指令抽取实际上就是将这一部分指令用nop填充)</font>**

        * `registers_size = 3`
            + 该方法使用的寄存器数量，这里为 3。
        * `ins_size = 2`
            + 输入参数数量，这里为 2，包括 `this` 指针和参数 `a`。
        * `outs_size = 2`
            + 输出参数数量，这里为 2，可能用于调用 `System.out.println`。
        * `tries_size = 0`
            + `try/catch` 块的数量，这里为 0，表示没有异常处理。
        * `debug_info_off = 648`
            + 调试信息的文件偏移量。

**struct debug_info_item debug_info**

            + `line_start = 0xA`
                - 起始行号，这里为 10。
            + `parameters_size = 0x1`
                - 参数数量，这里为 1，即参数 `a`。
            + `parameter_names[1]`
                - 参数名称列表，这里包含参数 `a` 的名称。
            + `debug_opcode`
                - 调试操作码列表，包含以下指令：
                    * `DBG_SET_PROLOGUE_END`
                    * `Special opcode: line + 0, address + 0`
                    * `Special opcode: line + 1, address + 5`
                    * `DBG_END_SEQUENCE`

**ushort insns[6]**

                - 方法的字节码指令列表，包含以下指令：
                    * `insns[0] = 98`
                    * `insns[1] = 1`
                    * `insns[2] = 8302`
                    * `insns[3] = 3`
                    * `insns[4] = 32`
                    * `insns[5] = 14`

# 4.加固流程
上面我们详细的讲解了dex文件组成以及各区域的指令，下面我们结合加固来讲解如果将dex文件内的方法指令抽取出来。

先来一张图

![画板](https://cdn.nlark.com/yuque/0/2024/jpeg/28000648/1723706583061-d2d498d5-e16e-4178-9ec1-a35275fa386e.jpeg)

接下来我们就仔细的讲解每一步

## 4.1.解压apk
```plain
 public static void unZip(String zipPath, String dirPath) {
        try {
            File zip = new File(zipPath);
            File dir = new File(dirPath);
            if (dir.exists()) {
                FileUtils.deleteRecurse(dir);
            }
            java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(zip);
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry zipEntry = entries.nextElement();
                String name = zipEntry.getName();
                if (name.equals("META-INF/CERT.RSA") || name.equals("META-INF/CERT.SF")
                        || name.equals("META-INF/MANIFEST.MF")) {
                    continue;
                }
                if (!zipEntry.isDirectory()) {
                    File file = new File(dir, name);
                    if (file.exists()) {
                        String fileName = file.getName();
                        int count = 1;
                        for (String v : resConflictFiles.values()) {
                            if (v.equalsIgnoreCase(fileName)) {
                                count++;
                            }
                        }
                        String rename;
                        do {
                            rename = count + fileName;
                            file = new File(file.getParentFile(), rename);
                            count++;
                        } while (file.exists());
                        resConflictFiles.put(rename, fileName);
                    }
                    if (!file.getParentFile().exists()) {
                        file.getParentFile().mkdirs();
                    }
                    if (doNotCompress != null && zipEntry.getCompressedSize() == zipEntry.getSize()) {
                        doNotCompress.add(file.getAbsolutePath().replace(dir.getAbsolutePath() + File.separator, ""));
                    }
                    FileOutputStream fos = new FileOutputStream(file);
                    InputStream is = zipFile.getInputStream(zipEntry);
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, len);
                    }
                    is.close();
                    fos.close();
                }
            }
            zipFile.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
```

这一步很简单，没什么好说的

## <font style="color:#DF2A3F;">4.2.提取dex文件里面的方法指令</font>
我们直接看关键代码:

### 4.2.1.方法extractAllMethods 
```java
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

```

1. **初始化和设置：**
    - 创建一个空的 `instructionList` 列表，用于存储提取出来的指令。
    - 读取 DEX 文件（`dexFile`）的内容，并将其写入到一个输出文件（`outDexFile`）。
    - 初始化一个 `Dex` 对象，用于处理 DEX 文件。
    - 将输出文件封装为 `RandomAccessFile` 对象，以便后续的读写操作。
2. **获取类定义和数据：**
    - 从 DEX 文件中获取所有类定义（`classDefs`），这些类定义包含了每个类的元数据。
    - 遍历每个类定义，并根据 `excludeRule` 规则跳过不需要处理的类。
3. **处理每个类的 **`**class_data[]**`**：**
    - 检查类定义的 `class_data_off`，确保其不为 0，否则跳过该类。
    - 读取类的 `class_data[]`，获取类的直接方法（`DirectMethods`）和虚拟方法（`VirtualMethods`）。
    - 转换类名为人类可读的格式。
4. **提取方法指令：**
    - 对每个直接方法和虚拟方法，调用 `extractMethod` 方法来提取方法的 `codeItem`，并使用 `nop` 填充。
    - 如果提取成功，将指令添加到 `instructionList` 列表，并将其放入 JSON 数组中。
5. **处理异常和清理：**
    - 捕获异常并记录错误日志。
    - 在 `finally` 块中，关闭 `RandomAccessFile` 对象。
    - 如果 `dumpCode` 标志为 `true`，将提取的信息写入 JSON 文件中。
    - <!-- 这是一张图片，ocr 内容为： -->
![](https://cdn.nlark.com/yuque/0/2024/png/28000648/1723447005150-4714ca34-ab0b-4ba9-a5bd-f1bb08a6decc.png?x-oss-process=image%2Fformat%2Cwebp%2Fresize%2Cw_1500%2Climit_0)
6. **返回值：**
    - 返回提取的指令列表 `instructionList`。

**作用：**

这个方法的主要作用是从指定的 DEX 文件中提取所有方法的指令，可能用于逆向工程、代码分析或其他相关操作。通过处理 DEX 文件中的 `class_data[]`，它能够准确地提取每个方法的代码指令，并将这些指令记录下来以供进一步分析或使用。              

### 4.2.2.方法extractMethod(dex,randomAccessFile,classDef,method)
```java
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
            outRandomAccessFile.writeShort(0);
        }
        instruction.setInstructionsData(byteCode);
        outRandomAccessFile.seek(insnsOffset);
        //写入返回指令
        outRandomAccessFile.write(returnByteCodes);
        return instruction;
    }
```

+ **获取相关数据列表**：
+ 从 DEX 文件中获取字符串列表、类型名称列表、方法原型 ID 列表、方法 ID 列表等。
+ **获取方法的详细信息**：
+ 通过 `methodIndex` 获取对应的 `MethodId`，并进一步获取方法的原型 (`ProtoId`)、返回类型、方法名称和所属类名。
+ **处理特殊情况**：
+ 如果方法是 `native` 方法或抽象方法（`codeOffset` 为 0），则不做处理并返回 `null`。
+ **创建并初始化 **`**Instruction**`** 对象**：
+ 创建 `Instruction` 对象，并设置方法在 DEX 文件中的偏移量 (`insnsOffset`)、方法索引和指令数据大小。
+ **读取和处理方法的字节码**：
+ 读取方法的 `code_item`，并检查其指令长度。
+ 如果方法的指令容量不足以存储返回指令，则记录日志并跳过该方法，返回 `null`。
+ **替换字节码为 **`**nop**`** 指令**：
+ 遍历方法的指令，将原始指令读取到 `byteCode` 数组中，并在对应位置写入 `nop` 指令。
+ **写入返回指令**：
+ 将文件指针重新定位到 `insnsOffset`，并写入返回指令，确保方法在执行时能够正确返回。
+ **返回结果**：
+ 返回包含方法偏移量、索引、指令数据等信息的 `Instruction` 对象。

```java
  for (int i = 0; i < insnsCapacity; i++) {
            //classes1_extracted.dat 文件指针移动到到指定偏移位置
            outRandomAccessFile.seek(insnsOffset + (i * 2));
            byteCode[i * 2] = outRandomAccessFile.readByte();
            byteCode[i * 2 + 1] = outRandomAccessFile.readByte();
            outRandomAccessFile.writeShort(0);
        }
```

6.写入返回指令，最后别忘了写入返回指令： outRandomAccessFile.write(returnByteCodes);

### 4.2.3.getReturnByteCodes
注意：我们看下getReturnByteCodes：

```cpp
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
```

我们可以详细分析这些字节码数组的组成及其作用。每个数组包含了一个或多个 DEX 指令，具体如下：

**1. **`**returnVoidCodes**`

```plain

byte[] returnVoidCodes = {(byte)0x0e , (byte)(0x0)};
```

+ **字节码:**`0e 00`
+ **对应的 DEX 指令:**`return-void`
+ **作用:**
    - 这个字节码数组用于从 `void` 类型的方法中返回。指令 `0x0e` 是 `return-void` 的操作码，后面的 `0x00` 是填充字节，没有实际作用。

**2. **`**returnCodes**`

```plain

byte[] returnCodes = {(byte)0x12 , (byte)0x0 , (byte) 0x0f , (byte) 0x0};
```

+ **字节码:**`12 00 0f 00`
+ **对应的 DEX 指令:**`const/4 v0, #int 0` 和 `return v0`
+ **作用:**
    - 这个字节码数组包含两个指令：
        1. `0x12 00` 对应 `const/4 v0, #int 0`，用于将整数 `0` 加载到寄存器 `v0` 中。
        2. `0x0f 00` 对应 `return v0`，用于从方法返回 `v0` 寄存器中的值。
    - 这组指令通常用于返回基本类型（如 `int`、`float` 等）的默认值（例如 `0`）。

**3. **`**returnWideCodes**`

```plain

byte[] returnWideCodes = {(byte)0x16 , (byte)0x0 , (byte) 0x0 , (byte) 0x0, (byte) 0x10 , (byte) 0x0};
```

+ **字节码:**`16 00 00 00 10 00`
+ **对应的 DEX 指令:**`const-wide/16 v0, #long 0` 和 `return-wide v0`
+ **作用:**
    - 这个字节码数组包含两个指令：
        1. `0x16 00 00 00` 对应 `const-wide/16 v0, #long 0`，用于将长整型 `0` 加载到寄存器对 `v0, v1` 中。
        2. `0x10 00` 对应 `return-wide v0`，用于从方法返回双字宽度值（如 `long` 或 `double`）。
    - 这组指令通常用于返回 `long` 或 `double` 类型的默认值（例如 `0L` 或 `0.0`）。

**4. **`**returnObjectCodes**`

```plain

byte[] returnObjectCodes = {(byte)0x12 , (byte)0x0 , (byte) 0x11 , (byte) 0x0};
```

+ **字节码:**`12 00 11 00`
+ **对应的 DEX 指令:**`const/4 v0, #int 0` 和 `return-object v0`
+ **作用:**
    - 这个字节码数组包含两个指令：
        1. `0x12 00` 对应 `const/4 v0, #int 0`，用于将整数 `0` 加载到寄存器 `v0` 中（通常用于表示 `null`）。
        2. `0x11 00` 对应 `return-object v0`，用于从方法返回对象类型的值（这里通常是 `null`）。
    - 这组指令通常用于返回 `null` 对象。

这些字节码数组代表了方法的不同返回类型，每个数组包含一组指令，用于生成相应的返回操作。它们处理了 `void` 方法、基本数据类型、双字宽数据类型（如 `long` 和 `double`），以及对象类型的返回。通过这些字节码，你可以在方法结束时生成相应的返回指令，以确保方法正确地返回期望的值或状态

## 4.3.指令封装成MultiDexCode并写入文件
上面我们已经将每个类的每个方法的代码指令抽取出来了，那么这些代码指令我们要怎么将其操作，才能让我们再脱壳的时候，也可以说是方法执行的时候能完美的将其完美的回填到对应的方法中呢？我们再上面的分析中可以知道，我们的dex文件在内存中读取各种方法指令都得时候都是通过将dexfie文件转为dex对象然后读取各个字段再文件中的偏移量去获取各个字段的值，因此我们我们想到我们如果在脱壳的时候在进行指令回填的时候也需要通过读取dex，class，method ，insns这些字段的偏移量，那么我们就考虑封装两个java对象：

**MultiDexCode:**

```java
public class MultiDexCode {
    //File version
    private short version;
    //dex文件数量
    private short dexCount;
    //用于存储每个 DEX 文件的起始位置
    private List<Integer> dexCodesIndex;
    //用于存储 DexCode 对象
    private List<DexCode> dexCodes;
}
```

**DexCode:**

```java
public class DexCode {
    //dex文件里面的方法数量
    private Short methodCount;
    //方法指令数据的在dex文件中的索引，也就是偏移量
    private List<Integer> insnsIndex;
    //用于存储方法指令
    private List<Instruction> insns;
}
```

下面我们直接上代码：

```java
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
     *
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

```

每一行代码都加上了注释，这里我们就再总结下这两个方法：

** **`**makeMultiDexCode**`** 方法**

**作用：**

+ 这个方法的作用是将多个 DEX 文件的指令数据集合封装成一个 `MultiDexCode` 对象。

**步骤：**

1. **初始化文件偏移量**：`fileOffset` 用于追踪每个 DEX 文件在最终文件中的位置。
2. **创建 **`**MultiDexCode**`** 对象**：初始化 `MultiDexCode` 对象，并设置其版本号和 DEX 文件数量。
3. **更新文件偏移量**：根据 `version` 和 `dexCount` 占用的字节数更新 `fileOffset`。
4. **存储 DEX 文件的起始位置**：初始化 `dexCodeIndex` 列表，用于存储每个 DEX 文件的起始位置。并根据 DEX 文件的数量继续更新 `fileOffset`。
5. **遍历指令集合**：通过遍历 `multiDexInsns`，为每个 DEX 文件创建一个 `DexCode` 对象：
    - 设置 `DexCode` 对象的方法数量。
    - 更新 `fileOffset`。
    - 将指令数据存储到 `DexCode` 对象中。
    - 计算和更新每条指令在文件中的偏移量，方法索引，指令数据大小，并继续更新 `fileOffset`。
6. **存储和返回 **`**MultiDexCode**`** 对象**：将处理后的 `DexCode` 列表添加到 `MultiDexCode` 对象中，并返回。

** **`**writeMultiDexCode**`** 方法**

**作用：**

+ 这个方法将已经封装好的 `MultiDexCode` 对象写入到一个二进制文件中。

**步骤：**

1. **检查 **`**MultiDexCode**`** 是否为空**：如果 `MultiDexCode` 中没有任何 DEX 文件，直接返回。
2. **打开文件**：使用 `RandomAccessFile` 以读写模式打开文件。
3. **写入文件头信息**：
    - 写入文件版本号和 DEX 文件数量，确保数据是以小端序（Little Endian）写入。
4. **写入 DEX 文件的起始位置**：遍历 `dexCodesIndex` 列表，将每个 DEX 文件的起始位置写入文件。
5. **写入 DEX 文件的数据**：
    - 遍历 `DexCode` 对象列表，逐个写入方法数量。
    - 对于每个 `DexCode`，将指令的相关信息（方法索引、DEX 偏移量、指令数据大小、指令数据）逐一写入文件。
6. **关闭文件**：在 `finally` 块中确保文件关闭，以释放资源。

## 4.4.重新组装apk
我们上面讲了将dexfile里面的方法指令抽取出来封装成`MultiDexCode`并写入文件，代码如下：

```java
String dataOutputPath = getOutAssetsDir(apkOutDir).getAbsolutePath() + File.separator + "OoooooOooo";
MultiDexCodeUtils.writeMultiDexCode(dataOutputPath, multiDexCode);
```

可以看到我们是将抽取后的指令封装成了`MultiDexCode`对象并写入asstes/OoooooOooo文件，被抽取后的dex文件要怎么处理呢，我们是将其压缩成了一个zip文件，并将其与壳的dex进行合并组装到apk里面：

```java
    /**
     * 将抽取完指令的dex文件压缩成zip文件存储在assets目录下
     *
     * @param apkMainProcessPath
     */
    private void compressDexFiles(String apkMainProcessPath) {
        ZipUtils.compress(getDexFiles(apkMainProcessPath), getOutAssetsDir(apkMainProcessPath).getAbsolutePath() + File.separator + "i11111i111.zip");
    }
```

接着就是合并：

```java
private void combineDexZipWithShellDex(String apkMainProcessPath) {
        try {
            File shellDexFile = new File("shell-files/dex/classes.dex");
            File originalDexZipFile = new File(getOutAssetsDir(apkMainProcessPath).getAbsolutePath() + File.separator + "i11111i111.zip");
            byte[] zipData = com.android.dex.util.FileUtils.readFile(originalDexZipFile); // 以二进制形式读出zip
            byte[] unShellDexArray =  com.android.dex.util.FileUtils.readFile(shellDexFile); // 以二进制形式读出dex
            int zipDataLen = zipData.length;
            int unShellDexLen = unShellDexArray.length;
            System.out.println("zipDataLen: " + zipDataLen);
            System.out.println("unShellDexLen: " + unShellDexLen);
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

            String str = apkMainProcessPath + File.separator+ "classes.dex";
            File file = new File(str);
            if (!file.exists()) {
                file.createNewFile();
            }

            // 输出成新的dex文件
            FileOutputStream localFileOutputStream = new FileOutputStream(str);
            localFileOutputStream.write(newdex);
            localFileOutputStream.flush();
            localFileOutputStream.close();
            System.out.println("已生成新的Dex文件======" + str);
            // 删除dex的zip包
            FileUtils.deleteRecurse(originalDexZipFile);
        }catch (Exception e){
            e.printStackTrace();
        }

    }
```

## 4.5.重新签名apk
上面的指令提取封装写入文件，dex压缩与壳的class合并就基本上是加固流程的核心了，接下来就是将原apk的Application和ComponentFactor写入文件(脱壳的时候需要用到)，至于打包签名这里就不多赘述了

然后我们看下加固完成的包：

<!-- 这是一张图片，ocr 内容为： -->
![](https://cdn.nlark.com/yuque/0/2024/png/28000648/1723465750998-1603cbb0-4ce3-4e95-a73c-75af32c738a5.png)

classes.dex ：抽取指令后的dex的压缩包与壳dex 合并后dex文件

vwwwwwww:壳的so

Ooooooo:抽取后的指令封装成了`MultiDexCode`对象并写入asstes/OoooooOooo文件

app_acf:原apk的application（com.yeahka.app.App）

app_name:原apk的appComponentFactory

# 5.脱壳流程
上面的加固流程我们分析了apk的加固流程，<font style="color:#DF2A3F;">其核心就是将每个dex里面的每个类的每个方法的指令数据全部抽取出来封装成</font>`<font style="color:#DF2A3F;">MultiDexCode</font>`<font style="color:#DF2A3F;">对象并写入asstes/OoooooOooo文件，然后将抽取后的dex压缩成zip文件与壳dex合并成一个新的classes.dex.</font>

那么我们再脱壳的时候如何实现在方法执行的时候，自动回填方法指令呢？

我们还是先看一张流程图：

![画板](https://cdn.nlark.com/yuque/0/2024/jpeg/28000648/1723467381637-5174f5f9-7dcf-48f4-b8a5-bff6fe252f6e.jpeg)

<font style="color:rgb(31, 35, 40);">shell模块最终生成的dex文件和so文件将被集成到需要加壳的apk中。它的要功能有：</font>

+ <font style="color:rgb(31, 35, 40);">处理App的启动</font>
+ <font style="color:rgb(31, 35, 40);">替换dexElements</font>
+ <font style="color:rgb(31, 35, 40);">hook相关函数</font>
+ <font style="color:rgb(31, 35, 40);">调用目标Application</font>
+ <font style="color:rgb(31, 35, 40);">CodeItem文件读取</font>
+ <font style="color:rgb(31, 35, 40);">CodeItem填回</font>

下面我们仔细的讲解下每个流程

### 5.1.加载脱壳的so
我们知道在app启动时候，首先会启动我们代理的ProxyApplication，然后我们脱壳的逻辑都是放在native层处理的，因此当ProxyApplication启动时，我们应该最先加载so

在ProxyApplication的attachBaseContext中：

```java
ApplicationInfo applicationInfo = base.getApplicationInfo();
if (applicationInfo == null) {
    throw new NullPointerException("application info is null");
}
FileUtils.unzipLibs(applicationInfo.sourceDir, applicationInfo.dataDir);
EnvUtils.loadShellLibs(applicationInfo.dataDir, applicationInfo.sourceDir);
```

从上面的分析我们知道，脱壳的so是放在apk的asstes/vmmmmmm/libdexshadow.so路径下的，因此我们直接从apk中读取asstes/vmmmmmm/libdexshadow.so

```java
public static void unzipLibs(String sourceDir,String dataDir) {
        String abiName = EnvUtils.getAbiDirName(sourceDir);

        File libsOutDir = new File(dataDir + File.separator + Global.LIB_DIR + File.separator + abiName);
        FileUtils.unzipInNeeded(sourceDir,
                "assets/" + Global.ZIP_LIB_DIR + "/" + abiName + "/" + Global.SHELL_SO_NAME,
                libsOutDir.getAbsolutePath());
    }
public static void unzipInNeeded(String zipFilePath, String entryName, String outDir){
        long start = System.currentTimeMillis();
        File out = new File(outDir);
        if(!out.exists()){
            out.mkdirs();
        }

        long localFileCrc = 0L;
        File entryFile = new File(outDir + File.separator  + Global.SHELL_SO_NAME);
        if(entryFile.exists()){
            localFileCrc = getCrc32(entryFile);
        }
        try {
            ZipFile zip = new ZipFile(zipFilePath);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while(entries.hasMoreElements()){
                ZipEntry entry = entries.nextElement();

                if(entry.getName().equals(entryName)) {
                    if(localFileCrc != entry.getCrc()) {
                        byte[] buf = new byte[4096];
                        int len = -1;

                        FileOutputStream fileOutputStream = new FileOutputStream(entryFile);
                        BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(fileOutputStream);
                        BufferedInputStream bufferedInputStream = new BufferedInputStream(zip.getInputStream(entry));
                        while ((len = bufferedInputStream.read(buf)) != -1) {
                            bufferedOutputStream.write(buf, 0, len);
                        }
                        Log.d(TAG, "unzip '" + entry.getName() + "' success. local = " + localFileCrc + ", zip = " + entry.getCrc());

                        FileUtils.close(bufferedOutputStream);
                        break;
                    }
                    else {
                        Log.w(TAG, "no need unzip");
                    }
                }
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        Log.d(TAG, "unzip libs took: " + (System.currentTimeMillis() - start) + "ms" );
    }
```

以上代码就是从apk中读取asstes/vmmmmmm/libdexshadow.so ，逻辑很简单也很固定的写法，这里不多赘述

### 5.2.处理相关函数的hook
这一步非常重要，在native层应该是最先处理的，因此我们将其封装成一个函数init_dexShadow定义如下:

```java
#define INIT_ARRAY_SECTION __attribute__ ((constructor))
INIT_ARRAY_SECTION void init_dexShadow();
```

这是 GNU C 编译器的一个扩展属性，用于指定某个函数在程序启动时（main() 函数执行之前）自动执行。通常用于执行一些初始化工作。

我们看看init_dexShadow做了啥：

```java
void init_dexShadow() {
    DLOGI("init_dexShadow call!");
    dexShadow_hook();
    createAntiRiskProcess();
}
```

我们首先是处理了hook相关函数的逻辑，首先我们要hook两个系统函数mmap和defineClass

#### 5.2.1.hook mmap函数
我们为啥要hook mmap这个函数呢，原因如下：

1.我们需要使用mprotect函数将内存中的dex文件属性设为可写

2.如果直接调用mprotect函数去修改属性会失败，原因是mprotect主要通过VM_MAYWRITE控制内存是否可被设置为“可写”，该属性的设置时机在mmap调用之时

3.通过hook mmap函数，或者在mmap之前修改传入mmap的标签，直接将内存属性修改为“可写”

关于mmap函数hook修改读写属性的资料大家可以参阅：[[原创]Android加壳过程中mprotect调用失败的原因及解决方案-Android安全-看雪-安全社区|安全招聘|kanxue.com](https://bbs.kanxue.com/thread-266527.htm)

看下代码：

```java
/**
 * hook 系统mmap函数
 */
void hook_mmap() {
    bytehook_stub_t stub = bytehook_hook_single(
            getArtLibName(),
            "libc.so",
            "mmap",
            (void *) fake_mmap,
            nullptr,
            nullptr);
    if (stub != nullptr) {
        DLOGD("mmap hook success!");
    }
}
void *fake_mmap(void *__addr, size_t __size, int __prot, int __flags, int __fd, off_t __offset) {
    BYTEHOOK_STACK_SCOPE();

    int prot = __prot;
    int hasRead = (__prot & PROT_READ) == PROT_READ;
    int hasWrite = (__prot & PROT_WRITE) == PROT_WRITE;

    char link_path[128] = {0};
    snprintf(link_path, sizeof(link_path), "/proc/%d/fd/%d", getpid(), __fd);
    char fd_path[256] = {0};
    readlink(link_path, fd_path, sizeof(fd_path));

    if (strstr(fd_path, "webview.vdex") != nullptr) {
        DLOGW("fake_mmap link path: %s, no need to change prot", fd_path);
        goto tail;
    }

    if (hasRead && !hasWrite) {
        //修改为可写
        prot = prot | PROT_WRITE;
        DLOGD("fake_mmap call fd = %d,size = %zu, prot = %d,flag = %d", __fd, __size, prot,
              __flags);
    }

    if (g_sdkLevel == 30) {
        if (strstr(fd_path, "base.vdex") != nullptr) {
            DLOGE("fake_mmap want to mmap base.vdex");
            __flags = 0;
        }
    }
    tail:
    void *addr = BYTEHOOK_CALL_PREV(fake_mmap, __addr, __size, prot, __flags, __fd, __offset);
    return addr;
}
```

这里我们采用的字节跳动的开源库[https://github.com/bytedance/bhook](https://github.com/bytedance/bhook) 来hook的 这是一种got hook，常用于拦截动态链接库（如 libc）中的函数调用，特别适用于拦截外部库函数，如 `malloc`, `free` ，`mmap`等标准库函数。

+ **原理**：
+ `GOT hook` 是通过修改全局偏移表（Global Offset Table, GOT）来实现钩子的。GOT 是动态链接库函数的地址表，程序调用外部函数时会通过 GOT 查找函数的实际地址。
+ 通过修改 GOT 中的函数地址，可以让程序调用你的钩子函数而不是原始函数。
+ **应用场景**：
+ 常用于拦截动态链接库（如 `libc`）中的函数调用。
+ 特别适用于拦截外部库函数，如 `malloc`, `free` 等标准库函数。
+ **优缺点**：
+ **优点**：稳定性更高，通常不会破坏目标函数的指令流。适用于拦截动态库函数。
+ **缺点**：只能拦截动态链接的函数调用，对于静态链接或内联的函数无效。

#### 5.2.2.hook defineClass函数
关于defineClass函数，我们首先要知道class的加载流程，才能知道为啥我们要hook defineClass，class的加载流程如下：

具体详细的流程我们这里不多赘诉了，我们在二代加固的时候接讲过，总结如下：

1.ClassLoader.java::loadClass

2.DexFile.java::defineClass

3.class_linker.cc::DefineClass

4.class_linker.cc::LoadClass

5.class_linker.cc::LoadClassMembers

6.class_linker.cc::LoadMethod

也就是说，当一个类被加载，它是会去调用DefineClass函数的，我们看一下它的函数原型：

```plain
mirror::Class* ClassLinker::DefineClass(Thread* self,
                                        const char* descriptor,
                                        size_t hash,
                                        Handle<mirror::ClassLoader> class_loader,
                                        const DexFile& dex_file,
                                        const DexFile::ClassDef& dex_class_def);
```

<font style="color:rgb(31, 35, 40);">DefineClass函数的参数很巧，有DexFile结构，还有ClassDef结构，我们通过Hook这个函数就知道以下信息：</font>

+ <font style="color:rgb(31, 35, 40);">加载的类来自哪个dex文件</font>
+ <font style="color:rgb(31, 35, 40);">加载类的数据的偏移</font>

<font style="color:rgb(31, 35, 40);">第一条可以帮助我们大致定位到存储的CodeItem的位置；第二条可以帮助我们找到CodeItem具体存储的位置以及填充到的位置。</font>

<font style="color:rgb(31, 35, 40);">来看一下ClassDef的定义：</font>

```plain
struct ClassDef {
    uint32_t class_idx_;  // index into type_ids_ array for this class
    uint32_t access_flags_;
    uint32_t superclass_idx_;  // index into type_ids_ array for superclass
    uint32_t interfaces_off_;  // file offset to TypeList
    uint32_t source_file_idx_;  // index into string_ids_ for source file name
    uint32_t annotations_off_;  // file offset to annotations_directory_item
    uint32_t class_data_off_;  // file offset to class_data_item
    uint32_t static_values_off_;  // file offset to EncodedArray
};
```

我们在dex文件详解的时候也分析过这个ClassDef这里不多说了

<font style="color:rgb(31, 35, 40);">其中最重要的字段就是</font>`<font style="color:rgb(31, 35, 40);">class_data_off_</font>`<font style="color:rgb(31, 35, 40);">它的值是当前加载的类的具体数据在dex文件中的偏移，通过这个字段就可以顺藤摸瓜定位到当前加载类的所有函数的在内存中CodeItem的具体位置。所以我们hook defineClass函数的目的就是：</font><font style="color:#DF2A3F;">在类加载之前对类的数据进行自定义处理，例如修改类中的方法、字段、或者注入新的逻辑，通过修改类的定义，可以注入新的方法或者修改现有方法的实现，从而实现代码注入，其实我们最终就是要把我们抽取出来的方法数据指令再注入到指定方法中去</font>

hook defineClass代码如下：

```cpp
/**
 * hook 系统DefineClass函数
 */
void hook_DefineClass() {
    void *defineClassAddress = DobbySymbolResolver(GetClassLinkerDefineClassLibPath(),
                                                   getClassLinkerDefineClassSymbol());
    if (g_sdkLevel >= __ANDROID_API_L_MR1__) {
        DobbyHook(defineClassAddress, (void *) DefineClassV22, (void **) &g_originDefineClassV22);
    } else {
        DobbyHook(defineClassAddress, (void *) DefineClassV21, (void **) &g_originDefineClassV21);

    }
}
void *DefineClassV22(void* thiz,void* self,
                     const char* descriptor,
                     size_t hash,
                     void* class_loader,
                     const void* dex_file,
                     const void* dex_class_def) {

    if(LIKELY(g_originDefineClassV22 != nullptr)) {

        patchClass(descriptor,dex_file,dex_class_def);

        return g_originDefineClassV22( thiz,self,descriptor,hash,class_loader, dex_file, dex_class_def);

    }
    return nullptr;
}
```

+ 通过 `DobbySymbolResolver` 找到系统中 `DefineClass` 函数的地址。
+ 根据 Android 系统的版本，选择对应的 `DefineClass` 函数版本，并使用 `DobbyHook` 函数将系统的 `DefineClass` 函数替换为自定义的 `DefineClassV22` 或 `DefineClassV21` 函数。
+ 被 Hook 后，系统在加载类时会调用自定义的 `DefineClassV22` 或 `DefineClassV21`，而不是原有的系统实现。

这里简单介绍下dobby [https://github.com/jmpews/Dobby](https://github.com/jmpews/Dobby) 这也是一个实现native层hook的第三方库，这是一种inline hook，什么时候inline hook呢？

+ **原理**：
+ `inline hook` 是通过直接修改目标函数的指令来实现钩子的。这通常涉及在目标函数的开头插入一段跳转指令，使程序跳转到自定义的钩子函数。
+ 当钩子函数执行完后，通常还会跳回目标函数的原始代码或特定的位置继续执行。
+ **应用场景**：
+ 用于拦截和修改程序中某个具体函数的行为，尤其是目标函数不可替代或无法通过其他手段劫持的情况。
+ 常用于调试、逆向工程、安全研究等领域。
+ **优缺点**：
+ **优点**：非常强大，可以拦截任意代码位置的执行。
+ **缺点**：容易破坏程序的稳定性，尤其是在多线程环境中；对目标函数的指令集有较强的依赖性，需要详细了解汇编和二进制代码结构。

我们继续看下patchClass函数：

```cpp
/**
 * 通过 Hook DefineClass 方法并在类加载时对类的数据进行修改，可以实现对类的动态修改和增强，从而实现自定义的功能和逻辑
 * 读取 static_fields_size、instance_fields_size、direct_methods_size、virtual_methods_size。
 * 根据读取到的字段和方法大小，读取具体的字段和方法数据。
 * 遍历直接方法和虚拟方法，并调用 patchMethod 进行处理。
 * @param descriptor 类的描述符（名称）
 * @param dex_file DEX 文件的指针
 * @param dex_class_def DEX 文件中 class_def 的指针
 */
void patchClass(__unused const char* descriptor,
                const void* dex_file,
                const void* dex_class_def) {

    if(LIKELY(dex_file != nullptr)){
        std::string location;//location 用于保存 DEX 文件的位置
        uint8_t *begin = nullptr; //begin 是 DEX 文件的起始地址
        uint64_t dexSize = 0;//dexSize 是 DEX 文件的大小
        //根据 Android SDK 的版本号来决定如何处理 DEX 文件。
        // 如果 SDK 版本大于或等于 Android P (API Level 28)，则使用 V28::DexFile 结构体；否则使用 V21::DexFile 结构体。
        // 这是因为不同的 Android 版本中 DEX 文件结构可能有所不同。
        if(g_sdkLevel >= __ANDROID_API_P__){
            auto* dexFileV28 = (V28::DexFile *)dex_file;
            location = dexFileV28->location_;
            begin = (uint8_t *)dexFileV28->begin_;
            dexSize = dexFileV28->size_;
        }
        else {
            auto* dexFileV21 = (V21::DexFile *)dex_file;
            location = dexFileV21->location_;
            begin = (uint8_t *)dexFileV21->begin_;
            dexSize = dexFileV21->size_;
        }

        if(location.rfind(DEXES_ZIP_NAME) != std::string::npos && dex_class_def){
            //获取dex的下标索引
            int dexIndex = parse_dex_number(&location);
            //获取类的定义，并记录类的索引和类数据的偏移量。
            auto* class_def = (dex::ClassDef *)dex_class_def;
            NLOG("[+] DefineClass class_idx_ = 0x%x,class data off = 0x%x",class_def->class_idx_,class_def->class_data_off_);

            if(LIKELY(class_def->class_data_off_ != 0)) {
                //如果类数据的偏移量不为 0，计算类数据在 DEX 文件中的实际位置。
                size_t read = 0;
                auto *class_data = (uint8_t *) ((uint8_t *) begin + class_def->class_data_off_);

                //依次读取类中静态字段、实例字段、直接方法和虚拟方法的数量。
                // 每个数量值都是用 ULEB128 编码方式存储的，因此需要逐步解析。
                uint64_t static_fields_size = 0;
                read += DexFileUtils::readUleb128(class_data, &static_fields_size);
                NLOG("[-] DefineClass static_fields_size = %lu,read = %zu", static_fields_size,
                     read);

                uint64_t instance_fields_size = 0;
                read += DexFileUtils::readUleb128(class_data + read, &instance_fields_size);
                NLOG("[-] DefineClass instance_fields_size = %lu,read = %zu",
                     instance_fields_size, read);

                uint64_t direct_methods_size = 0;
                read += DexFileUtils::readUleb128(class_data + read, &direct_methods_size);
                NLOG("[-] DefineClass direct_methods_size = %lu,read = %zu",
                     direct_methods_size, read);

                uint64_t virtual_methods_size = 0;
                read += DexFileUtils::readUleb128(class_data + read, &virtual_methods_size);
                NLOG("[-] DefineClass virtual_methods_size = %lu,read = %zu",
                     virtual_methods_size, read);

                //解析类的数据部分，分别读取静态字段、实例字段、直接方法和虚拟方法的信息，并存储在相应的数组中。
                dex::ClassDataField staticFields[static_fields_size];
                read += DexFileUtils::readFields(class_data + read, staticFields,
                                                 static_fields_size);

                dex::ClassDataField instanceFields[instance_fields_size];
                read += DexFileUtils::readFields(class_data + read, instanceFields,
                                                 instance_fields_size);

                dex::ClassDataMethod directMethods[direct_methods_size];
                read += DexFileUtils::readMethods(class_data + read, directMethods,
                                                  direct_methods_size);

                dex::ClassDataMethod virtualMethods[virtual_methods_size];
                read += DexFileUtils::readMethods(class_data + read, virtualMethods,
                                                  virtual_methods_size);
                //遍历直接方法和虚拟方法，分别调用 patchMethod 函数对每个方法进行修补。
                for (uint64_t i = 0; i < direct_methods_size; i++) {
                    auto method = directMethods[i];
                    NLOG("[-] DefineClass directMethods[%lu] methodIndex = %u,code_off = 0x%x",
                         i, method.method_idx_delta_, method.code_off_);
                    patchMethod(begin, location.c_str(), dexSize, dexIndex,
                                method.method_idx_delta_, method.code_off_);
                }

                for (uint64_t i = 0; i < virtual_methods_size; i++) {
                    auto method = virtualMethods[i];
                    NLOG("[-] DefineClass virtualMethods[%lu] methodIndex = %u,code_off = 0x%x",
                         i, method.method_idx_delta_, method.code_off_);
                    patchMethod(begin, location.c_str(), dexSize, dexIndex,
                                method.method_idx_delta_, method.code_off_);
                }
            }
            else {
                NLOG("class_def->class_data_off_ is zero");
            }
        }
    }
}
```

虽然已经写了详细的注释，但我们还是给大家总结下:

**1.检查 **`**dex_file**`** 是否为空：**

+ 如果 `dex_file` 为空，则不执行任何操作。

**2.获取 DEX 文件的信息：**

+ 根据当前的 Android SDK 版本，选择不同的结构体来提取 DEX 文件的 `location`（位置）、`begin`（起始地址）和 `dexSize`（文件大小）。

**3.检查 DEX 文件是否为目标文件：**

+ 如果 `location` 包含 `DEXES_ZIP_NAME` 且 `dex_class_def` 不为空，继续执行。
+ 解析 DEX 文件的索引 `dexIndex`。

**4.提取类定义信息：**

+ 记录类的索引和类数据的偏移量。
+ 如果类数据偏移量不为 0，继续执行。

**5.解析类的结构信息：**

+ 读取类的静态字段、实例字段、直接方法、虚拟方法的数量。

**6.解析类中的字段和方法：**

+ 解析并存储静态字段、实例字段、直接方法和虚拟方法的信息。

**7.遍历并修补方法：**

+ 遍历直接方法和虚拟方法，分别调用 `patchMethod` 函数对每个方法进行修补。

再看下patchMethod函数:

```cpp
/**
 * 修补 DEX 文件中的方法代码。它会先检查方法是否需要修补，如果需要，则从 dexMap 中查找对应的代码项，并进行修补
 * @param begin dex 文件的起始地址。
 * @param location dex 文件的位置（未使用）
 * @param dexSize dex 文件的大小
 * @param dexIndex dex 文件的索引
 * @param methodIdx 方法索引
 * @param codeOff 方法字节码在 dex 文件中的偏移量
 */
void patchMethod(uint8_t *begin,__unused const char *location,uint32_t dexSize,int dexIndex,uint32_t methodIdx,uint32_t codeOff){
    if(codeOff == 0){
        NLOG("[*] patchMethod dex: %d methodIndex: %d no need patch!",dexIndex,methodIdx);
        return;
    }
    //通过 codeOff 计算方法代码项在 DEX 文件中的实际位置，并将其转换为 dex::CodeItem 结构体指针。
    auto *dexCodeItem = (dex::CodeItem *) (begin + codeOff);

    //检查方法代码的第一条指令，如果不是指定的指令（0x0012, 0x0016, 0x000e），表示该方法已经有代码，不需要修补，直接返回。
    uint16_t firstDvmCode = *((uint16_t*)dexCodeItem->insns_);
    if(firstDvmCode != 0x0012 && firstDvmCode != 0x0016 && firstDvmCode != 0x000e){
        NLOG("[*] this method has code no need to patch");
        return;
    }

    //从 dexMap 中查找当前 DEX 文件的索引 dexIndex 是否存在
    auto dexIt = dexMap.find(dexIndex);
    if (LIKELY(dexIt != dexMap.end())) {
        //检查 dexMemMap 中是否存在该 DEX 文件的内存映射
        auto dexMemIt = dexMemMap.find(dexIndex);
        if(UNLIKELY(dexMemIt == dexMemMap.end())){
            //调用 change_dex_protective 函数改变 DEX 文件的保护属性。
            change_dex_protective(begin,dexSize,dexIndex);
        }
        //查找 dexMap 中当前方法的 methodIdx，如果找到相应的 CodeItem，则获取方法的实际代码指针 realCodeItemPtr。
        auto codeItemMap = dexIt->second;
        auto codeItemIt = codeItemMap->find(methodIdx);

        if (LIKELY(codeItemIt != codeItemMap->end())) {
            data::CodeItem* codeItem = codeItemIt->second;
            auto *realCodeItemPtr = (uint8_t *)(dexCodeItem->insns_);
            //如果找到了相应的 CodeItem，则将其内容复制到实际的代码位置，完成修补。如果没有找到相应的 CodeItem，记录日志，表示找不到对应的方法代码项。
            NLOG("[*] patchMethod codeItem patch, methodIndex = %d,insnsSize = %d >>> %p(0x%lx)",codeItem->getMethodIdx(), codeItem->getInsnsSize(), realCodeItemPtr,(realCodeItemPtr - begin));
            memcpy(realCodeItemPtr,codeItem->getInsns(),codeItem->getInsnsSize());
        }
        else{
            NLOG("[*] patchMethod cannot find  methodId: %d in codeitem map, dex index: %d(%s)",methodIdx,dexIndex,location);
        }
    }
    else{
        DLOGE("[*] patchMethod cannot find dex: %d in dex map",dexIndex);
    }
}
```

这里也总结下：

**1.检查方法的代码偏移量：**

+ 如果 `codeOff` 为 0，表示方法没有代码，不需要修补，直接返回。

**2.获取方法的代码项指针：**

+ 通过 `codeOff` 计算方法代码项在 DEX 文件中的实际位置。

**3.检查方法的第一条指令：**

+ 检查方法代码的第一条指令是否需要修补。如果不需要，则直接返回。

**4.查找 DEX 文件的索引：**

+ 从 `dexMap` 中查找当前 DEX 文件的索引 `dexIndex`。
+ 如果存在，检查 `dexMemMap` 中是否存在该 DEX 文件的内存映射。如果不存在，则改变 DEX 文件的保护属性。

**5.查找并修补方法的代码：**

+ 在 `dexMap` 中查找对应的 `methodIdx`。
+ 如果找到相应的 `CodeItem`，将其内容复制到方法的实际代码位置，完成修补。
+ 如果没有找到，记录日志。

通过以上分析，我们就基本搞清楚了我们hook defineClass函数的目的，那么我们看完可能要问

```cpp
//如果找到了相应的 CodeItem，则将其内容复制到实际的代码位置，完成修补。如果没有找到相应的 CodeItem，记录日志，表示找不到对应的方法代码项。
NLOG("[*] patchMethod codeItem patch, methodIndex = %d,insnsSize = %d >>> %p(0x%lx)",codeItem->getMethodIdx(), codeItem->getInsnsSize(), realCodeItemPtr,(realCodeItemPtr - begin));
memcpy(realCodeItemPtr,codeItem->getInsns(),codeItem->getInsnsSize());
```

这个codeItem是哪来的，因为我们是要从codeItem中找到对用method的方法指令，其实这个codeItem里面的数据就是从我们打包加固过程中写入到assets/vmmmmwwww/Ooooooo这个文件中读取的，我们继续讲解

### 5.3.将本地apk映射到内存中
我们在加固的时候将每个dex的每个class的每个method的方法指令insns抽取出去封装成MultiDexCode并写入assets/vmmmmwwww/Ooooooo文件，还将原apk的application和appComponentFactory写入到app_name和app_acf中，因此我们再脱壳的时候需要讲这些文件读取出来，传统的做法是直接从本地apk中读取zipEntry，但这样再一些大型apk中性能较差，因此我们将本地apk通过mmap函数映射到内存中

```cpp
void *apk_addr = nullptr;
size_t apk_size = 0;
load_apk(env, &apk_addr, &apk_size);
/**
 * 内存加载dex
 * @param zip_file_path
 * @param zip_addr
 * @param zip_size
 */
static void load_zip(const char* zip_file_path,void **zip_addr,size_t *zip_size) {
    DLOGD("load_zip by mmap");
    load_zip_by_mmap(zip_file_path, zip_addr, zip_size);

    DLOGD("%s start: %p size: %zu" ,__FUNCTION__ , *zip_addr,*zip_size);
}
void load_apk(JNIEnv *env,void **apk_addr,size_t *apk_size) {
    char apkPathChs[512] = {0};
    getApkPath(env,apkPathChs,ARRAY_LENGTH(apkPathChs));
    load_zip(apkPathChs,apk_addr,apk_size);

}
/**
 * 使用 mmap64 将文件映射到内存中，并存储映射地址和文件大小
   使用 mmap64 将文件映射到内存中，比传统的文件I/O方法有许多优点，
   包括更高的性能、更简单的代码和更高的内存利用效率。
   对于处理大文件或需要频繁访问文件内容的应用程序，内存映射是一种更为高效和简洁的方法
 * @param zip_file_path 原apk文件地址
 * @param zip_addr 映射到内存中的地址
 * @param zip_size
 */
static void load_zip_by_mmap(const char* zip_file_path,void **zip_addr,size_t *zip_size) {
    int fd = open(zip_file_path,O_RDONLY);
    if(fd <= 0){
        DLOGE("load_zip cannot open file!");
        return;
    }
    struct stat fst;
    fstat(fd,&fst);
    const int page_size = sysconf(_SC_PAGESIZE);
    const size_t need_zip_size = (fst.st_size / page_size) * page_size + page_size;
    DLOGD("%s fst.st_size = " FMT_INT64_T ",need size = %zu" ,__FUNCTION__ ,fst.st_size,need_zip_size);
    *zip_addr = mmap64(nullptr,
                       need_zip_size,
                       PROT_READ ,
                       MAP_PRIVATE,
                       fd,
                       0);

    *zip_size = fst.st_size;

    DLOGD("%s addr:" FMT_POINTER " size: %zu" ,__FUNCTION__ ,(uintptr_t)*zip_addr,*zip_size);
}
```

### 5.4.读取并封装方法数据指令
我们在加固的时候将每个dex的每个class的每个method的方法指令insns抽取出去封装成MultiDexCode并写入assets/vmmmmwwww/Ooooooo文件，那么我们在脱壳的时候也应该把方法指令读取出来。

#### 5.4.1.内存读取方法指令数据文件
看代码：

```cpp
 void *codeItemFilePtr = nullptr;//存储读取zip内容的地址的指针
 if (codeItemFilePtr == nullptr) {
    read_zip_file_entry(apk_addr, apk_size, CODE_ITEM_NAME_IN_ZIP, &codeItemFilePtr,
                        &entry_size);
 } else {
    DLOGD("no need read codeitem from zip");
 }
 readCodeItem((uint8_t *) codeItemFilePtr, entry_size);
```

首先看 read_zip_file_entry 这个函数，因为我们的目的是要把assets/vmmmmwwww/Ooooooo的数据读取出来并赋值给codeItemFilePtr，传统的做法是，直接从本地apk文件中读取，但是经过研究发现，如果我们的apk体积很大条目很多，直接从本地读取性能较差，因此我们将本地apk通过mmap函数映射到内存中，然后通过[minizip-ng](https://github.com/zlib-ng/minizip-ng)直接从内存中读取assets/vmmmmwwww/Ooooooo条目的数据

我们直接看read_zip_file_entry代码：

```cpp
/**
 * 从一个已经映射到内存中的ZIP文件中读取一个指定的文件条目。它使用了minizip库提供的函数来操作ZIP文件
 * @param zip_addr 内存中ZIP文件的起始地址
 * @param zip_size ZIP文件的大小
 * @param entry_name 要读取的条目的名称
 * @param entry_addr 指向将存储读取内容的地址的指针
 * @param entry_size 指向存储条目大小的指针
 * @return bool类型，指示是否需要释放分配的内存
 */
bool read_zip_file_entry(void *zip_addr, off_t zip_size, const char *entry_name, void **entry_addr,
                         uint64_t *entry_size) {
    DLOGD("read_zip_file_entry prepare read file: %s", entry_name);

    void *mem_stream = nullptr;
    void *zip_handle = nullptr;
    bool needFree = false;

    mz_stream_mem_create(&mem_stream);
    mz_stream_mem_set_buffer(mem_stream, zip_addr, zip_size);
    mz_stream_open(mem_stream, nullptr, MZ_OPEN_MODE_READ);

    mz_zip_create(&zip_handle);
    int32_t err = mz_zip_open(zip_handle, mem_stream, MZ_OPEN_MODE_READ);

    if (err == MZ_OK) {
        err = mz_zip_goto_first_entry(zip_handle);
        while (err == MZ_OK) {
            mz_zip_file *file_info = nullptr;
            err = mz_zip_entry_get_info(zip_handle, &file_info);

            if (err == MZ_OK) {
                if (strncmp(file_info->filename, entry_name, 256) == 0) {
                    DLOGD("read_zip_file_entry found entry name = %s,file size = " FMT_INT64_T,
                          file_info->filename,
                          file_info->uncompressed_size);

                    err = mz_zip_entry_read_open(zip_handle, 0, nullptr);
                    if (err != MZ_OK) {
                        DLOGW("read_zip_file_entry not prepared: %d", err);
                        continue;
                    }
                    needFree = true;
                    DLOGD("read_zip_file_entry compress method is: %d",
                          file_info->compression_method);

                    *entry_addr = calloc(file_info->uncompressed_size + 1, 1);
                    DLOGD("read_zip_file_entry start read: %s", file_info->filename);

                    __unused size_t bytes_read = mz_zip_entry_read(zip_handle, *entry_addr,
                                                                   file_info->uncompressed_size);

                    DLOGD("read_zip_file_entry reading entry: %s,read size: %zu", entry_name,
                          bytes_read);

                    *entry_size = (file_info->uncompressed_size);

                    goto tail;
                } // strncmp
            } else {
                DLOGW("read_zip_file_entry get entry info err: %d", err);
                break;
            }
            err = mz_zip_goto_next_entry(zip_handle);
        } // while
    } else {
        DLOGW("read_zip_file_entry zip open fail: %d", err);
    } // zip open

    tail:
    {
        return needFree;
    }
}

```

这个函数就将assets/vmmmmwwww/Ooooooo的数据读取出来并赋值给codeItemFilePtr了

#### 5.4.2.封装方法指令数据结构
那么接下来就是如何将读取出来的codeItemFilePtr解析成native层对应的MultiDexCode，跟加固的时候一样，<font style="color:#DF2A3F;">我们在native层也需要创建MultiDexCode和CodeItem两个类</font>

MultiDexCode.h：

```cpp
//
//  Created by 周瑾 on 2024/7/10
//

#ifndef YKDEXSHADOW_MULTIDEXCODE_H
#define YKDEXSHADOW_MULTIDEXCODE_H

#include <stdint.h>
#include "CodeItem.h"
#include "common/dexshadow_log.h"
namespace dexshadow {
    namespace data {
        class MultiDexCode {
        private:
            size_t m_size;
            uint8_t *m_buffer;
        public:
            static MultiDexCode *getInst();

            void init(uint8_t *buffer, size_t size);

            uint8_t readUInt8(uint32_t offset);

            uint16_t readUInt16(uint32_t offset);

            uint32_t readUInt32(uint32_t offset);

            uint16_t readVersion();

            uint16_t readDexCount();

            uint32_t *readDexCodeIndex(int *count);

            dexshadow::data::CodeItem *nextCodeItem(uint32_t *offset);
        };
    }
}



#endif //YKDEXSHADOW_MULTIDEXCODE_H

```

MultiDexCode.cpp：

```cpp
//
//  Created by 周瑾 on 2024/7/10
//

#include "MultiDexCode.h"
/**
 * getInst 方法返回 MultiDexCode 类的静态实例。这是实现单例模式的方式，确保在整个程序运行期间只存在一个 MultiDexCode 对象
 * @return
 */
dexshadow::data::MultiDexCode* dexshadow::data::MultiDexCode::getInst(){
    static auto *m_inst = new MultiDexCode();
    return m_inst;
}
/**
 * init 方法初始化 MultiDexCode 对象，设置内部缓冲区指针 m_buffer 和缓冲区大小 m_size。buffer 指向DEX文件的数据，size 是数据的大小
 * @param buffer 指向DEX文件的数据
 * @param size 数据的大小
 */
void dexshadow::data::MultiDexCode::init(uint8_t* buffer, size_t size){
    this->m_buffer = buffer;
    this->m_size = size;
}
/**
 * 读取版本号 readVersion 方法从缓冲区的第一个字节（偏移量为0）读取版本号。它调用 readUInt16 方法读取16位无符号整数。
 * @return
 */
uint16_t dexshadow::data::MultiDexCode::readVersion(){
    return readUInt16(0);
}
/**
 * 读取DEX文件数量 readDexCount 方法从缓冲区的第3个字节（偏移量为2）读取DEX文件的数量。它同样调用 readUInt16 方法
 * @return
 */
uint16_t dexshadow::data::MultiDexCode::readDexCount(){
    return readUInt16(2);
}
/**
 * 读取DEX代码索引 readDexCodeIndex 方法读取DEX代码索引。它首先读取DEX文件的数量，
 * 然后将其存储在 count 指针指向的位置。返回的指针指向缓冲区中存储DEX代码索引的部分，索引从第5个字节（偏移量为4）开始。
 * @param count 读取DEX文件的数量，然后将其存储在 count 指针指向的位置
 * @return 返回的指针指向缓冲区中存储DEX代码索引的部分，索引从第5个字节（偏移量为4）开始。
 */
uint32_t* dexshadow::data::MultiDexCode::readDexCodeIndex(int* count){
    uint16_t dexCount = readDexCount();
    *count = dexCount;
    return (uint32_t*)(m_buffer + 4);
}
/**
 * nextCodeItem 方法的作用是从缓冲区中读取下一个代码项（CodeItem），并更新偏移量以指向下一个代码项
 * @param offset 一个指向无符号32位整数的指针，表示当前读取的偏移量。这个偏移量会被更新，以指向下一个代码项的位置
 * @return
 */
dexshadow::data::CodeItem* dexshadow::data::MultiDexCode::nextCodeItem(uint32_t* offset) {
    uint32_t methodIdx = readUInt32(*offset);//从当前偏移量（*offset）开始，读取一个32位无符号整数，表示方法索引
    uint32_t offsetOfDex = readUInt32(*offset + 4);//从当前偏移量加4的位置开始，读取一个32位无符号整数，表示DEX文件中的偏移量
    uint32_t insnsSize = readUInt32(*offset + 8);//从当前偏移量加8的位置开始，读取一个32位无符号整数，表示指令的大小
    auto* insns = (uint8_t*)(m_buffer + *offset + 12);//从当前偏移量加12的位置开始，读取指令数据。insns 是一个指向指令数据的指针
    *offset = (*offset + 12 + insnsSize);//更新偏移量，将其设置为当前偏移量加上12（前面的字段大小）和指令大小，以指向下一个代码项的位置
    auto* codeItem = new CodeItem(methodIdx,offsetOfDex,insnsSize,insns);//创建并返回 CodeItem 对象

    return codeItem;
}

uint8_t dexshadow::data::MultiDexCode::readUInt8(uint32_t offset){
    uint8_t t = *((uint8_t*)(m_buffer + offset));

    return t;
}

uint16_t dexshadow::data::MultiDexCode::readUInt16(uint32_t offset){
    uint16_t t = *((uint16_t*)(m_buffer + offset));

    return t;
}

uint32_t dexshadow::data::MultiDexCode::readUInt32(uint32_t offset){
    uint32_t t = *((uint32_t*)(m_buffer + offset));

    return t;
}
```

总结下 MultiDexCode：

+ **单例模式：**
+ `MultiDexCode::getInst()`：使用单例模式，确保整个程序中只有一个 `MultiDexCode` 实例。这是通过静态变量实现的。
+ **初始化缓冲区：**
+ `MultiDexCode::init(uint8_t* buffer, size_t size)`：初始化 `MultiDexCode` 对象，将提供的缓冲区指针和大小存储到类的成员变量中。缓冲区指向的是包含多个 DEX 文件的数据。
+ **读取缓冲区中的基本信息：**
+ `readVersion()`：从缓冲区的起始位置读取并返回 DEX 文件的版本号。
+ `readDexCount()`：读取并返回缓冲区中包含的 DEX 文件的数量。
+ **读取 DEX 文件的代码索引：**
+ `readDexCodeIndex(int* count)`：读取并返回 DEX 代码索引的指针，同时将 DEX 文件的数量存储在 `count` 中。代码索引指针从缓冲区的第 5 个字节开始。
+ **读取下一个代码项（CodeItem）：**
+ `nextCodeItem(uint32_t* offset)`：
    - 从指定的偏移量位置读取下一个代码项。
    - 代码项包含方法索引、DEX 文件中的偏移量、指令大小和指令数据。
    - 读取这些信息后，创建一个 `CodeItem` 对象并返回。
    - 更新偏移量指针，以指向下一个代码项。
+ **读取缓冲区中的特定数据类型：**
+ `readUInt8(uint32_t offset)`：从指定的偏移量读取一个 8 位无符号整数。
+ `readUInt16(uint32_t offset)`：从指定的偏移量读取一个 16 位无符号整数。
+ `readUInt32(uint32_t offset)`：从指定的偏移量读取一个 32 位无符号整数。

注意：<font style="color:#DF2A3F;">我们在读取字段时候的偏移量就是我们在加固阶段写入文件内的偏移量，这个一定要对上。</font>

我们在MultiDexCode::nextCodeItem函数中读取到了codeItem的信息并创建了codeItem，接下来我们再看下：

CodeItem.h:

```cpp
//
//  Created by 周瑾 on 2024/7/10
//

#ifndef YKDEXSHADOW_CODEITEM_H
#define YKDEXSHADOW_CODEITEM_H

#include <stdint.h>

namespace dexshadow {
    namespace data {
        class CodeItem {
        private:
            uint32_t mMethodIdx;
            uint32_t mOffsetDex;
            uint32_t mInsnsSize;
            uint8_t *mInsns;
        public:
            uint32_t getMethodIdx() const;

            void setMethodIdx(uint32_t methodIdx);

            uint32_t getOffsetDex() const;

            void setOffsetDex(uint32_t offsetDex);

            uint32_t getInsnsSize() const;

            void setInsnsSize(uint32_t size);

            uint8_t *getInsns() const;

            void setInsns(uint8_t *insns);

            CodeItem();

            CodeItem(uint32_t methodIdx, uint32_t offsetDex, uint32_t size, uint8_t *insns);

            ~CodeItem();
        };
    }
}

#endif

```

CodeItem.cpp:

```cpp
//
// Created by 周瑾 on 2024/7/10
//
#include "CodeItem.h"
/**
 * 获取方法索引
 * @return 当前代码项的方法索引
 */
uint32_t dexshadow::data::CodeItem::getMethodIdx() const {
    return mMethodIdx;
}
/**
 * 设置方法索引
 * @param methodIdx
 */
void dexshadow::data::CodeItem::setMethodIdx(uint32_t methodIdx) {
    CodeItem::mMethodIdx = methodIdx;
}
/**
 * 获取DEX偏移
 * @return
 */
uint32_t dexshadow::data::CodeItem::getOffsetDex() const {
    return mOffsetDex;
}
/**
 * 设置DEX偏移
 * @param offsetDex
 */
void dexshadow::data::CodeItem::setOffsetDex(uint32_t offsetDex) {
    CodeItem::mOffsetDex = offsetDex;
}
/**
 * 获取指令大小
 * @return
 */
uint32_t dexshadow::data::CodeItem::getInsnsSize() const {
    return mInsnsSize;
}
/**
 * 设置指令大小
 * @param size
 */
void dexshadow::data::CodeItem::setInsnsSize(uint32_t size) {
    CodeItem::mInsnsSize = size;
}
/**
 * 获取指令数据
 * @return
 */
uint8_t *dexshadow::data::CodeItem::getInsns() const {
    return mInsns;
}
/**
 * 设置指令数据
 * @param insns
 */
void dexshadow::data::CodeItem::setInsns(uint8_t *insns) {
    CodeItem::mInsns = insns;
}
/**
 * 构造函数
 * @param methodIdx 方法索引
 * @param offsetDex DEX偏移
 * @param size 指令大小
 * @param insns 指令数据的指针
 */
dexshadow::data::CodeItem::CodeItem(uint32_t methodIdx, uint32_t offsetDex, uint32_t size,
                   uint8_t *insns): mMethodIdx(methodIdx), mOffsetDex(offsetDex), mInsnsSize(size), mInsns(insns) {

}
/**
 * 析构函数
 */
dexshadow::data::CodeItem::~CodeItem() {

}

```

+ **私有成员:**
    - `mMethodIdx`：存储方法索引。
    - `mOffsetDex`：存储在 DEX 文件中的偏移量。
    - `mInsnsSize`：存储指令的大小。
    - `mInsns`：指向指令数据的指针。
+ **构造函数和析构函数:**
    - 构造函数：用方法索引、DEX 偏移量、指令大小和指令数据指针初始化 CodeItem 对象。。
    - 析构函数：释放 CodeItem 对象占用的资源（如果有）。
    - `mInsnsSize`：存储指令的大小。
    - `mInsns`：指向指令数据的指针。
+ **公有成员函数:**
    - `getMethodIdx`：获取当前 `CodeItem` 对象的方法索引。

**            **`setMethodIdx`：设置 `CodeItem` 对象的方法索引。

`getOffsetDex`：获取 DEX 文件中的偏移量。

`setOffsetDex`：设置 DEX 文件中的偏移量。

`getInsnsSize`：获取指令的大小。

`setInsnsSize`：设置指令的大小。

`getInsns`：获取指令数据的指针。

`setInsns`：设置指令数据的指针。

这个类用于表示一个 DEX 文件中的方法项，包括其在 DEX 文件中的索引、偏移量、指令大小和指令数据，并提供了相应的访问和修改这些属性的方法

### 5.5.解析方法指令数据
我们在4.1.2.3.1中将assets/vmmmmwwww/Ooooooo的数据读取出来并赋值给codeItemFilePtr，在4.1.2.3.2中封装了好了对应的数据结构，那么如果将codeItemFilePtr解析并封装成对应的MultiDexCode和CodeItem呢？接着往下看：

readCodeItem函数

```cpp

/**
 * 从内存中的数据读取并解析 CodeItem 信息，并将这些信息存储在一个全局的 dexMap 中
 * @param data 指向包含 CodeItem 数据的内存的指针 codeItemFilePtr
 * @param data_len 数据的长度
 */
void readCodeItem(uint8_t *data, size_t data_len) {

    if (data != nullptr && data_len >= 0) {
        //获取 MultiDexCode 实例
        data::MultiDexCode *dexCode = data::MultiDexCode::getInst();
        //调用 MultiDexCode 的 init 方法，用传入的数据缓冲区和长度进行初始化
        dexCode->init(data, data_len);
        //读取版本和 DEX文件数量 并打印下
        DLOGI("readCodeItem : version = %d , dexCount = %d", dexCode->readVersion(),
              dexCode->readDexCount());
        //读取 DEX 代码索引，并将索引的数量存储在 indexCount 中
        int indexCount = 0;
        uint32_t *dexCodeIndex = dexCode->readDexCodeIndex(&indexCount);
        for (int i = 0; i < indexCount; i++) {
            //在C++中，数组和指针有着非常紧密的关系。数组名实际上是指向数组第一个元素的指针。因此，你可以使用指针算术来访问数组中的元素
            //*(dexCodeIndex + i) 等同于 dexCodeIndex[i]
            DLOGI("readCodeItem : dexCodeIndex[%d] = %d", i, *(dexCodeIndex + i));
            //获取 DEX 代码偏移和方法数量
            uint32_t dexCodeOffset = *(dexCodeIndex + i);
            uint16_t methodCount = dexCode->readUInt16(dexCodeOffset);

            DLOGD("readCodeItem : dexCodeOffset[%d] = %d,methodCount[%d] = %d", i, dexCodeOffset, i,
                  methodCount);
            //这行代码创建一个新的 unordered_map，用于存储方法索引和对应的 CodeItem 对象。
            auto codeItemMap = new std::unordered_map<int, data::CodeItem *>();
            /**
             * registers_size   : 0x000A (2 bytes)
                ins_size         : 0x0002 (2 bytes)
                outs_size        : 0x0003 (2 bytes)
                tries_size       : 0x0000 (2 bytes)
                debug_info_off   : 0x00000000 (4 bytes)
                insns_size       : 0x00000010 (4 bytes)
                insns            : (ushort array, variable length)
             */
             //这里加2是因为 写入的methodCount是在short类型占两个字节
            uint32_t codeItemIndex = dexCodeOffset + 2;
            for (int k = 0; k < methodCount; k++) {
                data::CodeItem *codeItem = dexCode->nextCodeItem(&codeItemIndex);
                uint32_t methodIdx = codeItem->getMethodIdx();
                //这行代码将 methodIdx 和对应的 codeItem 插入到 codeItemMap 中。
                codeItemMap->insert(std::pair<int, data::CodeItem *>(methodIdx, codeItem));
            }
            //这行代码将 codeItemMap 插入到 dexMap 中，其中 i (dex文件数量)作为键。
            dexMap.insert(
                    std::pair<int, std::unordered_map<int, data::CodeItem *> *>(i, codeItemMap));

        }
        DLOGD("readCodeItem map size = %lu", (unsigned long) dexMap.size());
    }
}
```

这个函数执行完我们就将每个dex里面所有的类的所有的方法的的method_id以及对应的codeItem封装成一个map,我们再patchMethod函数里面在修复方法的时候回去读取这个map。到这里我们对抽取出来的方法指令的操作就完毕了，接下来就是一些脱壳的常规操作了

### 5.6.加载抽取完指令的dex文件
我们在加固阶段是将所有dex文件的方法指令抽取出来了，然后将抽完的dex压缩成dex.zip并于壳的dex进行合并成为一个新的classes.dex。那么剩余的dex我们需要手动将其提取出来释放到本地，用classLoader进行加载，这一步跟我们一代加固是一样的了

```cpp
/**
 * 提取dex文件 其实就是 将i11111i111.zip 写入/data/user/0/com.yeahka.app/code_cache/i11111i111.zip
 * @param env
 * @param apk_addr
 * @param apk_size
 */
void extractDexesInNeeded(JNIEnv *env, void *apk_addr, size_t apk_size) {
    char compressedDexesPathChs[256] = {0};
    getCompressedDexesPath(env, compressedDexesPathChs, ARRAY_LENGTH(compressedDexesPathChs));

    char codeCachePathChs[256] = {0};
    getCodeCachePath(env, codeCachePathChs, ARRAY_LENGTH(codeCachePathChs));

    if (my_access(codeCachePathChs, F_OK) == 0) {
        if (my_access(compressedDexesPathChs, F_OK) != 0) {
            writeDexAchieve(compressedDexesPathChs, apk_addr, apk_size);
            chmod(compressedDexesPathChs, 0444);
            DLOGI("extractDexes %s write finish", compressedDexesPathChs);

        } else {
            DLOGI("extractDexes dex files is achieved!");
        }
    } else {
        if (mkdir(codeCachePathChs, 0775) == 0) {
            writeDexAchieve(compressedDexesPathChs, apk_addr, apk_size);
            chmod(compressedDexesPathChs, 0444);
        } else {
            DLOGE("WTF! extractDexes cannot make code_cache directory!");
        }
    }
}
```

以上代码都是些文件读写权限检查操作，我们直接看如何写入：

writeDexAchieve函数

```cpp
static uint32_t readZipLength(const uint8_t *data, size_t size) {
    if (size < 4) return 0;

    uint32_t length = 0;
    memcpy(&length, data + size - 4, 4);

    // Byte swapping for little-endian format
    length = (length >> 24) | ((length & 0x00FF0000) >> 8) | ((length & 0x0000FF00) << 8) | (length << 24);

    return length;
}
/**
 * 从classes.dex中分离出zip文件并 写入/data/user/0/com.yeahka.app/code_cache/i11111i111.zip
 * @param dexAchievePath
 * @param apk_addr
 * @param apk_size
 */
static void writeDexAchieve(const char *dexAchievePath, void *apk_addr, size_t apk_size) {
    DLOGD("zipCode open = %s", dexAchievePath);
    FILE *fp = fopen(dexAchievePath, "wb");
    if (fp != nullptr) {
        uint64_t dex_files_size = 0;
        void *dexFilesData = nullptr;
        bool needFree = read_zip_file_entry(apk_addr, apk_size, COMBINE_DEX_FILES_NAME_IN_ZIP,
                                            &dexFilesData, &dex_files_size);
        if (dexFilesData != nullptr) {
            //读取zip文件的长度
            uint32_t zipDataLen = readZipLength((uint8_t *)dexFilesData, dex_files_size);

            if (zipDataLen > 0 && dex_files_size > zipDataLen + 4) {
                // Zip文件数据从classes.dex的末尾开始减去Zip长度和长度字段的4个字节
                uint8_t *zipDataStart = (uint8_t *)dexFilesData + (dex_files_size - zipDataLen - 4);
                fwrite(zipDataStart, 1, zipDataLen, fp);
                DLOGD("Zip file extracted and written successfully.");
            } else {
                DLOGE("Invalid zip data length: %u. dex_files_size: %lu", (unsigned int)zipDataLen, (unsigned long)dex_files_size);
            }
        } else {
            DLOGE("Failed to read classes.dex.");
        }
        fclose(fp);
        if (needFree) {
            DEX_SHADOW_FREE(dexFilesData);
        }
    } else {
        DLOGE("WTF! zipCode write fail: %s", strerror(errno));
    }
}
```

这个函数就是读取apk的classes.dex赋值给dexFilesData，然后从dexFilesData按照我们接入的长度将dex的zip数据读取出来并写入到/data/user/0/com.yeahka.app/code_cache，<font style="color:#DF2A3F;">这里特别要注意，因为我们将dex文件释放到code_cache目录，这个目录是系统生成用来缓存dex和优化过的dex文件的，因此我们不需要自定义classLoader去加载这些dex，只需要用系统的classLoader即可</font>

### 5.7.合并dexElements
合并dexElements的目的我们再一代二代加固里面都讲解过了，这里再简单说一下 

**1. **`**ClassLoader**`** 的运行机制**

+ **初始加载**：
    - 在应用启动时，`ClassLoader` 会加载应用的所有 DEX 文件，并将它们转换成 `DexPathList` 中的 `dexElements`。这些 `dexElements` 是 DEX 文件中类和方法的实际表示。
+ **静态加载 vs 动态加载**：
    - 初始启动时，`ClassLoader` 只能加载它知道的 DEX 文件，这些文件通常位于应用的 `APK` 或初始 DEX 文件中。
    - 如果后续有新添加的 DEX 文件，`ClassLoader` 并不会自动感知和加载这些新文件，因此需要手动进行处理。

**2. 新 DEX 文件的识别与使用**

+ **动态添加的 DEX 文件**：
    - 当你将一个新的 DEX 文件释放到 `code_cache` 目录后，它只是被放置在磁盘上。`ClassLoader` 并不会自动将这些文件纳入它的类加载路径。
+ `**makePathElements**`** 的作用**：
    - `makePathElements` 方法的作用是将指定路径中的 DEX 文件解析为 `Element` 对象。这些对象包含了该路径中的所有类和资源的信息。
    - 如果不进行这个步骤，`ClassLoader` 将无法识别和加载这些新添加的 DEX 文件。

**3. 合并到原有的 **`**dexElements**`

+ **保持类加载的完整性**：
    - 应用启动后，`ClassLoader` 已经加载了初始的 DEX 文件。这些文件的内容被保存在 `dexElements` 数组中。
    - 如果我们只是将新的 DEX 文件路径添加到 `ClassLoader` 中，而不合并现有的 `dexElements`，那么这些新添加的类和资源可能无法正常加载。
+ **顺序加载**：
    - 合并后，新的 `dexElements` 包含了所有旧的和新添加的 DEX 文件内容。系统会按顺序尝试从这些 `dexElements` 中加载类，这样就可以确保新的类和资源能够被正确加载和使用。
+ **避免类加载冲突**：
    - 合并过程还确保了加载路径的正确顺序，避免新旧 DEX 文件中可能存在的类定义冲突。

大家如果想深入了解动态加载和类加载机制可以参阅[动态加载和类加载机制](https://bbs.kanxue.com/thread-271538.htm)，我们接下来直接看代码：

mergeDexElement

```cpp
/**
 * DEX 文件合并到一个目标 ClassLoader 中，从而使这些 DEX 文件中的类可以被加载和使用
 * @param env
 * @param targetClassLoader
 * @param pathChs
 */
void mergeDexElement(JNIEnv *env, jclass __unused, jobject targetClassLoader, const char *pathChs) {
    jobjectArray extraDexElements = makePathElements(env, pathChs);

    //创建一个 BaseDexClassLoader 的包装对象，用于操作目标 ClassLoader
    dalvik_system_BaseDexClassLoader targetBaseDexClassLoader(env, targetClassLoader);

    //获取目标 ClassLoader 的 DexPathList 对象，该对象包含当前已经加载的 DEX 文件信息。
    jobject originDexPathListObj = targetBaseDexClassLoader.getPathList();

    //将获取到的 DexPathList 对象封装成 dalvik_system_DexPathList 对象，用于后续操作。
    dalvik_system_DexPathList targetDexPathList(env, originDexPathListObj);

    //获取当前 DexPathList 中已加载的 DEX 元素数组。
    jobjectArray originDexElements = targetDexPathList.getDexElements();

    //获取新添加的 DEX 元素数组和原始 DEX 元素数组的长度。
    jsize extraSize = env->GetArrayLength(extraDexElements);
    jsize originSize = env->GetArrayLength(originDexElements);

    //创建一个新的 DEX 元素数组，其大小为原始数组大小加上新添加的数组大小。
    dalvik_system_DexPathList::Element element(env, nullptr);
    jclass ElementClass = element.getClass();
    jobjectArray newDexElements = env->NewObjectArray(originSize + extraSize, ElementClass,
                                                      nullptr);

    //将原始的 DEX 元素数组复制到新创建的 DEX 元素数组中。
    for (int i = 0; i < originSize; i++) {
        jobject elementObj = env->GetObjectArrayElement(originDexElements, i);
        env->SetObjectArrayElement(newDexElements, i, elementObj);
    }

    for (int i = originSize; i < originSize + extraSize; i++) {
        jobject elementObj = env->GetObjectArrayElement(extraDexElements, i - originSize);
        env->SetObjectArrayElement(newDexElements, i, elementObj);
    }

    //将新添加的 DEX 元素数组复制到新创建的 DEX 元素数组中。
    targetDexPathList.setDexElements(newDexElements);

    DLOGD("mergeDexElement success");
}
```

```cpp
/**
 * makePathElements 方法用于创建新的 PathElement 对象数组，该数组包含指定路径中的 DEX 文件
 * @param env
 * @param pathChs
 * @return
 */
jobjectArray makePathElements(JNIEnv *env, const char *pathChs) {
    jstring path = env->NewStringUTF(pathChs);//将字符数组转换为 jstring，表示 DEX 文件的路径。
    java_io_File file(env, path);//创建一个 java.io.File 对象，表示 DEX 文件。

    //创建一个 ArrayList 对象，用于存储 DEX 文件；创建另一个 ArrayList 对象，用于存储可能的异常。
    java_util_ArrayList files(env);
    files.add(file.getInstance());
    java_util_ArrayList suppressedExceptions(env);

    clock_t cl = clock();
    jobjectArray elements;
    //调用java层的方法
    if (android_get_device_api_level() >= __ANDROID_API_M__) {
        elements = dalvik_system_DexPathList::makePathElements(env,
                                                               files.getInstance(),
                                                               nullptr,
                                                               suppressedExceptions.getInstance());
    } else {
        elements = dalvik_system_DexPathList::makeDexElements(env,
                                                              files.getInstance(),
                                                              nullptr,
                                                              suppressedExceptions.getInstance());
    }
    printTime("makePathElements success,took = ", cl);
    return elements;
}
```

注释已经很详细了

### 5.8.替换application并启动
这些流程我们就不多讲了 因为这是脱壳的固定流程，对这个流程还有问题的同学自己去翻看一代二代加固资料之前我们是在java层做的这些操作包括反射等，只不过这一次我们是放在native层实现而已

### 5.9.替换contentProvider
#### 5.9.1.分析contentProvider的安装启动流程
为什么要单独处理contentProvider呢？因为他比较特殊，Activity , Service , BroadcastReceiver 组件 , 创建时 , 都是在 Application 的 onCreate 方法完成之后进行创建 ，ContentProvider 组件的创建比较特殊 , 当系统发现该应用在 AndroidManifest.xml 中注册了 ContentProvider 组件时 , 会安装该 ContentProvider ，在 ActivityThread 中 , H ( Handler 子类 ) 接收到 BIND_APPLICATION 消息时 , 进行初始化 ContentProvider 组件的操作 , 调用 handleBindApplication() 方法 , 进行相关操作 ;

```cpp
public final class ActivityThread {

    private class H extends Handler {
        public static final int BIND_APPLICATION        = 110;

        public void handleMessage(Message msg) {
            switch (msg.what) {
                case BIND_APPLICATION:
                    Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "bindApplication");
                    AppBindData data = (AppBindData)msg.obj;
                    handleBindApplication(data);
                    Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                    break;
                
			} // switch
		} // handleMessage
	} // private class H extends Handler

}
```

在 handleBindApplication 方法中 , 先创建了 Application , 此时调用了 Application 的 attachBaseContext 方法 ：

```cpp
// If the app is being launched for full backup or restore, bring it up in
// a restricted environment with the base application class.
// ★ 创建 Application 
// ★ 此时调用 Application 的 attachBaseContext 方法
Application app = data.info.makeApplication(data.restrictedBackupMode, null);
mInitialApplication = app;

```

在这之后马上创建 ContentProvider , 此时还没有调用 Application 的 onCreate 方法 

```cpp
// don't bring up providers in restricted mode; they may depend on the
// app's custom Application class
// ★ 在此处创建 ContentProvider 
if (!data.restrictedBackupMode) {
    if (!ArrayUtils.isEmpty(data.providers)) {
		// ★ 安装 ContentProvider
        installContentProviders(app, data.providers);
        // For process that contains content providers, we want to
        // ensure that the JIT is enabled "at some point".
        mH.sendEmptyMessageDelayed(H.ENABLE_JIT, 10*1000);
    }
}

```

在创建了 ContentProvider 之后 , 才调用的 Application 的 onCreate 方法 ;

```cpp
// ★ 此处调用 Application 的 onCreate 方法
mInstrumentation.onCreate(data.instrumentationArgs);
```

ContentProvider 组件在代理 Application 类 ProxyApplication 的 attachBaseContext 方法之后 , onCreate 方法之前就创建了 , 而 代理 Application 类 ProxyApplication 替换为真实的 Application 类 MyApplication 是在 ProxyApplication 的 onCreate 方法中进行的 , 也就是说 ContentProvider 在 Application 替换之前就创建完成了 ;

接下来再分析下ActivityThread 中的 installContentProviders 方法：

ActivityThread 中的 installContentProviders 方法 , 传入两个参数 :

Context context 参数 : 之前创建的 Application 上下文对象 , 这个 Application 对象是替换前的 代理 Application 对象 , 是在 AndroidManifest.xml 中的 application 节点信息 ;

List<ProviderInfo> providers 参数 : ProviderInfo 集合 , 是为生成多个 ContentProvider 准备的 , ProviderInfo 与 ApplicationInfo 是相同的 , ApplicationInfo 是 AndroidManifest.xml 中的 application 节点信息 , ProviderInfo 是 AndroidManifest.xml 中的 provider 节点信息 ;

在该 installContentProviders 方法中 , 调用了 installProvider 为每个 ProviderInfo 各自创建一个 ContentProvider ;

```cpp
public final class ActivityThread {

	// ★ 传入两个参数 , 
	// ★ Context context : 之前创建的 Application 上下文对象 , 
	// 		这个 Application 对象是替换前的 代理 Application 对象 , 
	// 		是在 AndroidManifest.xml 中的 application 节点信息 , 
	// ★ List<ProviderInfo> providers , 这里的 ProviderInfo 集合 
	// ★ 是为生成多个 ContentProvider 准备的 
	// ★ ProviderInfo 与 ApplicationInfo 是相同的
	// ★ ApplicationInfo 是 AndroidManifest.xml 中的 application 节点信息 
	// ★ ProviderInfo 是 AndroidManifest.xml 中的 provider 节点信息 
    private void installContentProviders(
            Context context, List<ProviderInfo> providers) {
		// ★ 存放创建的多个 ContentProvider 
        final ArrayList<ContentProviderHolder> results = new ArrayList<>();

		// ★ 创建多个 ContentProvider 
        for (ProviderInfo cpi : providers) {
			
			// ★ 注意这里 installProvider 的第一个参数是 ProxyApplication 类型的
            ContentProviderHolder cph = installProvider(context, null, cpi,
                    false /*noisy*/, true /*noReleaseNeeded*/, true /*stable*/);
            if (cph != null) {
                cph.noReleaseNeeded = true;
                results.add(cph);
            }
        }
    }
	
}

```

在 installProvider 方法中 , 通过 反射创建 ContentProvider ;

```cpp
// ★ 反射创建 ContentProvider 
localProvider = (ContentProvider)cl.
    loadClass(info.name).newInstance();

```

在 创建 ContentProvider 之后 , 调用了 attachInfo 函数 , 注意此处与 Activity , Service , BrocastReceiver 不同 , 这三个组件创建后调用的是 attach 函数

```cpp
// XXX Need to create the correct context for this provider.
// ★ 创建 ContentProvider 之后 , 调用了 attachInfo 函数 
// 注意此处与 Activity , Service , BrocastReceiver 不同 , 
// 这三个组件创建后调用的是 attach 函数
localProvider.attachInfo(c, info);

```

这里分析 attachInfo 中的 c 参数 , 也就是 Context 上下文的获取过程 :

声明空的 Context c 对象

```cpp
// ★ 该上下文对象很重要 
Context c = null;

```

获取 ApplicationInfo 信息 ApplicationInfo ai , 即 AndroidManifest.xml 中配置的 application 节点信息

```cpp
// 该 ApplicationInfo 是 AndroidManifest.xml 中配置的 application 节点信息
ApplicationInfo ai = info.applicationInfo;

```

进行如下三个分支的判定 :

分支一 : if (context.getPackageName().equals(ai.packageName)) : 在应用中配置的代理 Application 包名与真实 Application 包名都是相等的 ;

分之二 : else if (mInitialApplication != null && mInitialApplication.getPackageName().equals(ai.packageName)) : 与分支一类似 , 也是要求包名相等 ;

分支三 : 上面两个分支没有命中 , 就执行第三个分支 ;

```cpp
			// ★ 该上下文对象很重要 
            Context c = null;
			// 该 ApplicationInfo 是 AndroidManifest.xml 中配置的 application 节点信息
            ApplicationInfo ai = info.applicationInfo;
			
			// 该 context 是 ProxyApplication , 代理 Application 
            if (context.getPackageName().equals(ai.packageName)) {
				// 在应用中配置的代理 Application 包名与真实 Application 包名都是相等的
				// 该分支是命中的 
                c = context;
            } else if (mInitialApplication != null &&
                    mInitialApplication.getPackageName().equals(ai.packageName)) {
				// 该分支中 mInitialApplication 就是 Context context 参数 , 肯定不为空 
				// 该分支无法命中 
                c = mInitialApplication;
            } else {
				
				// 上述两个分支都无法命中 , 才进入该分支 
				// 需要将代理 Application 的包名 与 真实应用的包名设置成不同的
				// 此时上面两个分支都无法命中 
                try {
                    c = context.createPackageContext(ai.packageName,
                            Context.CONTEXT_INCLUDE_CODE);
                } catch (PackageManager.NameNotFoundException e) {
                    // Ignore
                }
            }

```

上面的分支一 与 分支二 都是将 代理 Application 分支 , 因此必须要命中第三个分支 ;

如果将 代理 Application 的 getPackageName() 获取包名的方法返回空 , 此时肯定无法命中前两个分支 , 只能命中第三分支 ;

#### 5.9.2.重写getPackageName和createPackageContext
总结刚才的启动流程：ActivityThread 中的 installProvider 方法中的三个分支如下 , 在上面的分析中 , 如果要使得分支一 context.getPackageName().equals(ai.packageName) 与分支二 mInitialApplication.getPackageName().equals(ai.packageName) , 都无法命中 , 就需要 Application 的 getPackageName 方法获取的包名不等于在 AndroidManifest.xml 中的包名 ai.packageName , 这里重写 ProxyApplication 的 getPackageName 方法 , 使该方法返回值为 “” 字符串 , 这样就无法命中前两个分支 , 只能进入 else 分支 

<font style="color:#DF2A3F;">所以我们在代理application中需要重写getPackageName方法来命中分支三：</font>

```cpp
public class ProxyApplication extends Application {

    @Override
    public String getPackageName() {
        if(TextUtils.isEmpty(app_name)){
            // 如果 AndroidManifest.xml 中配置的 Application 全类名为空
            // 那么 不做任何操作
        }else{
            // 如果 AndroidManifest.xml 中配置的 Application 全类名不为空
            // 为了使 ActivityThread 的 installProvider 方法
            // 无法命中如下两个分支
            // 分支一 : context.getPackageName().equals(ai.packageName)
            // 分支二 : mInitialApplication.getPackageName().equals(ai.packageName)
            // 设置该方法返回值为空 , 上述两个分支就无法命中
            return "";
        }

        return super.getPackageName();
    }
    
}
```

<font style="color:#DF2A3F;">还需要在 ContextImpl 的 createPackageContext 方法执行前进行 Application 替换：</font>

```cpp
public class ProxyApplication extends Application {
    private static final String TAG = ProxyApplication.class.getSimpleName();
    private String realApplicationName = "";
    private Application realApplication = null;
    @Override
    public Context createPackageContext(String packageName, int flags) throws PackageManager.NameNotFoundException {
        Log.d(TAG, "createPackageContext: " + realApplicationName);
        if (!TextUtils.isEmpty(realApplicationName)) {
            replaceApplication();
            return realApplication;
        }
        return super.createPackageContext(packageName, flags);
    }
```

# 6.frida检测
frida是当今最强大最流行的逆向工具了，一个优秀的加固怎么能不做frida检测呢，我们来看下我们是如何在脱壳的时候检测frida注入的：

```cpp
/**
 * 创建防护进程函数
 */
void createAntiRiskProcess() {
    //创建一个新的进程，fork返回值在子进程中为0，在父进程中为子进程的PID。
    pid_t child = fork();
    if(child < 0) {//调用detectFrida函数，检查是否有Frida工具存在。
        DLOGW("%s fork fail!", __FUNCTION__);
        detectFrida();
    }
    else if(child == 0) {//如果是子进程，则进入此块。
        DLOGD("%s in child process", __FUNCTION__);
        //检查是否有Frida工具存在。
        detectFrida();
        //使子进程被ptrace跟踪。
        doPtrace();
    }
    else {//如果是父进程，则进入此块
        DLOGD("%s in main process, child pid: %d", __FUNCTION__, child);
        protectChildProcess(child);
        detectFrida();
    }
}
```

```cpp
//
// Created by 周瑾 on 2024/8/2.
//
#include "dexshadow_risk.h"

void crash() {
#ifdef __aarch64__
    asm volatile(
            "mov x30,#0\t\n"
            );
#elif __arm__
    asm volatile(
        "mov lr,#0\t\n"
    );
#elif __i386__
    asm volatile(
            "ret\t\n"
            );
#elif __x86_64__
    asm volatile(
            "pop %rbp\t\n"
    );
#endif
}

/**
 * 检测Frida线程函数
 * @param args
 * @return
 */
[[noreturn]] void *detectFridaOnThread(__unused void *args) {
    while (true) {
        //调用find_in_maps函数检查是否有名为frida-agent的共享对象
        int frida_so_count = find_in_maps(1,"frida-agent");
        if(frida_so_count > 0) {
            DLOGD("detectFridaOnThread found frida so");
            crash();
        }
        //调用find_in_threads_list函数检查是否有可疑线程
        int frida_thread_count = find_in_threads_list(4
                ,"pool-frida"
                ,"gmain"
                ,"gdbus"
                ,"gum-js-loop");

        if(frida_thread_count >= 2) {
            DLOGD("detectFridaOnThread found frida threads");
            crash();
        }
        DLOGD("detectFridaOnThread pass");
        sleep(10);
    }
}

/**
 * 启动一个新的线程来检测是否存在Frida工具。具体检测逻辑在detectFridaOnThread函数中实现。
 */
void detectFrida() {
    pthread_t t;
    pthread_create(&t, nullptr,detectFridaOnThread,nullptr);
}
/**
 * 调用ptrace系统调用，将子进程置于被跟踪状态，以防止被调试。
 */
void doPtrace() {
    __unused int ret = sys_ptrace(PTRACE_TRACEME,0,0,0);
    DLOGD("doPtrace result: %d",ret);
}
/**
 * 等待子进程的结束。如果检测到子进程结束，则认为可能发生了异常情况，触发crash函数。
 * @param args
 * @return
 */
void *protectProcessOnThread(void *args) {
    pid_t child = *((pid_t *)args);

    DLOGD("%s waitpid %d", __FUNCTION__ ,child);

    free(args);

    int pid = waitpid(child, nullptr, 0);
    if(pid > 0) {
        DLOGW("%s detect child process %d exited", __FUNCTION__, pid);
        crash();
    }
    DLOGD("%s waitpid %d end", __FUNCTION__ ,child);

    return nullptr;
}
/**
 * 创建一个线程来监视子进程的状态。如果子进程退出，则触发crash函数。
 * @param pid
 */
void protectChildProcess(pid_t pid) {
    pthread_t t;
    pid_t *child = (pid_t *) malloc(sizeof(pid_t));
    *child = pid;
    pthread_create(&t, nullptr,protectProcessOnThread,child);
}
```

总结：

**1. 进程分离与监控机制**

+ **进程分离**：代码通过`fork`函数创建一个子进程，父进程和子进程分别执行不同的安全任务。
    - **子进程**：执行安全检测操作，如检测Frida工具的存在，并通过`ptrace`使自己无法被调试。
    - **父进程**：监控子进程的运行状态，如果子进程异常退出，父进程立即终止应用程序，以防止应用继续运行在不安全的环境中。
+ **目的**：这种进程分离增加了调试的复杂性，并形成了双重保护机制，使得攻击者即使绕过了一个进程的保护，另一个进程仍然可以检测并响应。

**2. Frida 工具检测**

+ **内存映射检测 (**`**find_in_maps**`**)**：通过读取`/proc/<pid>/maps`文件，检查当前进程的内存映射中是否加载了Frida相关的共享库（如`frida-agent`）。
    - **功能**：检测是否有Frida工具加载在进程中。一旦发现，立即触发应用程序崩溃。
+ **线程检测 (**`**find_in_threads_list**`**)**：遍历当前进程的所有线程，检查是否存在与Frida相关的可疑线程（如`gmain`、`gdbus`等）。
    - **功能**：检测Frida工具是否通过特定的线程名称隐藏在进程中。如果检测到多个可疑线程，则认为存在安全威胁，触发应用程序崩溃。
+ **检测线程 (**`**detectFridaOnThread**`**)**：在一个独立线程中持续运行检测逻辑，定期检查是否有Frida相关的共享库或线程存在。
    - **功能**：确保应用程序在整个运行过程中都能及时发现并响应Frida工具的注入和调试。

**4. 进程监控与防护**

+ **子进程保护 (**`**protectChildProcess**`**)**：父进程创建一个线程来监控子进程的状态，确保子进程正常运行。
    - **功能**：如果子进程意外退出或被攻击导致崩溃，父进程检测到后会触发应用崩溃，以保护应用的完整性。


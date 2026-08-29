package com.wsteam.wandscape.compat.curios;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 回归测试：Curios 是 compileOnly 可选依赖——未安装时 {@code top.theillusivec4.curios.*} 类在运行时
 * 不存在。任何被"无 Curios 也加载"的类若在字节码里引用 Curios 类型，都会在类装载/验证期抛
 * {@code NoClassDefFoundError}，导致整个 mod 在启动期崩溃（1.11.0 曾因此崩溃）。
 *
 * <p>本测试守卫 {@link CuriosCompat} 门面的结构不变式：它禁止引用任何 Curios 类。Curios-typed 代码
 * 必须隔离到 {@link CuriosCompatImpl}，且仅在 {@code isLoaded()} 为真时被门控静态调用触达。
 */
class CuriosCompatSoftDependencyTest {

    @Test
    @DisplayName("CuriosCompat 门面字节码不得引用任何 Curios 类")
    void facadeMustNotReferenceCuriosClasses() throws IOException {
        byte[] classBytes;
        try (InputStream in = CuriosCompat.class.getResourceAsStream("CuriosCompat.class")) {
            classBytes = in.readAllBytes();
        }
        // ASCII 常量池 UTF8 序列在二进制中连续出现，Latin-1 逐字节解码不破坏 ASCII。
        String constantPool = new String(classBytes, StandardCharsets.ISO_8859_1);
        String marker = "top/theillusivec4/curios/";
        assertFalse(constantPool.contains(marker),
                "CuriosCompat 门面引用了 Curios 类（" + marker + "）——无 Curios 时将在类装载期抛 "
                        + "NoClassDefFoundError，导致 mod 启动崩溃；Curios 类型代码应隔离在 CuriosCompatImpl");
    }
}

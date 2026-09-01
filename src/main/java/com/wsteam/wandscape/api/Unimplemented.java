package com.wsteam.wandscape.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 API 面里「已声明、但尚未有实现落地」的方法/接口。
 *
 * <p>用途：API 重设计先行、实现层滞后时的诚实标注。实现方尚未提供真实现的方法一律
 * 以 {@literal default} 桩呈现，桩体抛 {@link UnsupportedOperationException}——调用即炸，
 * 绝不静默返回空值造成「看起来正常其实没生效」。实现落地后摘除本注解并补真实现。
 *
 * <p>习惯用法（接口内新增方法）：
 * <pre>{@code
 *   @Unimplemented("API redesign stage — 待实现")
 *   default void setColonyName(UUID colonyId, String name) {
 *       throw new UnsupportedOperationException("ColonyApi.setColonyName not yet implemented");
 *   }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Unimplemented {
    /** 说明为何尚未实现/计划在哪个阶段落地。默认空串。 */
    String value() default "";
}

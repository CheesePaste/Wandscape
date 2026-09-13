package com.wsteam.wandscape.compat.tlm;

import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;

/**
 * 向车万女仆注册本模组的官方女仆任务。
 *
 * <p>TLM 用 ASM 扫描所有模组 class 上的 {@link LittleMaidExtension} 注解，反射调用无参构造后
 * 依次调各 {@code addXxx}——**不需要** META-INF/services、不需要事件、不需要在我们这边注册任何东西。
 * 注解本身没有 {@code @Retention(RUNTIME)} 也无所谓，扫的是 class 文件常量池。
 *
 * <p>约束（TLM 要求）：public 类 + public 无参构造；任务注册发生在
 * {@code FMLCommonSetupEvent}，之后 {@code TaskManager} 的任务表会被冻结，注册不了第二个。
 *
 * <p>本类引用 TLM 类型，所以只在 TLM 加载时才会被 TLM 实例化并链接；
 * TLM 缺席时它只是一个躺在 jar 里、没有任何东西来碰的 class 文件。
 */
@LittleMaidExtension
public class TlmExtension implements ILittleMaid {

    @Override
    public void addMaidTask(TaskManager manager) {
        manager.add(new ColonyWorkerMaidTask());
    }
}

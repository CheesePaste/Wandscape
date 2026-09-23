package com.wsteam.wandscape.foundation.networking;

import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.log.LogCategory;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 客户端 S2C 处理派发表——按 payload 类型查表分发到客户端实现。
 *
 * <p>取代此前「每个包一个 {@code private static Consumer<X> clientHandler} +
 * {@code setClientHandler}」的注入方式：那种写法要求 31 个包各持有一个可变静态字段，
 * 且分发处要重复判空。这里收成一张表，绑定方改调 {@link #bind}，包类只需转发。
 *
 * <p>为什么本类不 import 任何客户端类：{@code RegisterPayloadHandlersEvent} 在专用服务端
 * 也会触发，注册墙引用的类必须能被服务端安全装载。客户端 lambda 由
 * {@code WandscapeClient.onClientSetup} 在客户端启动时绑定进来，本类自身只认类型与回调。
 */
public final class ClientPayloadDispatcher {

    private ClientPayloadDispatcher() {}

    private static final Map<CustomPacketPayload.Type<?>, Consumer<?>> HANDLERS = new ConcurrentHashMap<>();

    /**
     * 绑定某类 S2C 包的客户端处理器。由客户端初始化阶段调用，重复绑定同类型以后者为准。
     */
    public static <T extends CustomPacketPayload> void bind(CustomPacketPayload.Type<T> type, Consumer<T> handler) {
        HANDLERS.put(type, handler);
    }

    /**
     * 按 payload 类型分发到已绑定的客户端处理器。
     *
     * <p>未绑定的类型不抛异常：只有客户端会收到 S2C 包，而客户端启动即完成全部绑定；
     * 真出现未绑定说明绑定遗漏或包在绑定前抵达，记一次警告后丢弃，避免整条连接被打断。
     */
    @SuppressWarnings("unchecked")
    public static void dispatch(CustomPacketPayload payload) {
        Consumer<CustomPacketPayload> handler = (Consumer<CustomPacketPayload>) HANDLERS.get(payload.type());
        if (handler == null) {
            Log.warnOnce(LogCategory.NETWORK, "unbound:" + payload.type().id(),
                    "Received payload {} but no client handler is bound — dropped", payload.type().id());
            return;
        }
        handler.accept(payload);
    }
}

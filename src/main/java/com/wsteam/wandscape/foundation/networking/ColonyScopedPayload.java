package com.wsteam.wandscape.foundation.networking;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import javax.annotation.Nullable;

/**
 * 包自己声明「目标小镇 + 拒止文案」的客户端 → 服务端包，由 {@link PayloadRegistry#c2s} 一处接管。
 *
 * <p>背景（见 {@code docs/multiplayer-survey.md} §10.5.2）：全仓约 30 个服务端包各自在
 * {@code handleServer} 里手写归属校验，改一次权限模型就要改 30 处、漏一处就是越权洞。
 * 真正的落点不是「再包一层」，而是让包声明它的作用域，判定与拒止集中在一处。
 *
 * <p>只实现本接口的包才走网关；既有包不实现，行为完全不变（它们的自查仍在）。判定语义见
 * {@link ColonyScope#admit}：按**档位**而不是按「是不是我创始的镇」——后者会把
 * MANAGER / MEMBER / ALLY 全部拒掉，与四档模型冲突。
 *
 * <p>零 MC 逻辑，只是声明：网关怎么用作用域由 {@link ColonyScope} 决定。
 */
public interface ColonyScopedPayload extends CustomPacketPayload {

    /**
     * 本包的作用域；返回 {@code null} 或 {@link ColonyScope#NONE} 表示**不过网关**
     * （例如被邀方本人的「接受/拒绝」——他此时还不是成员，无档位可判，由 handler 按邀请校验）。
     */
    @Nullable
    ColonyScope colonyScope();
}

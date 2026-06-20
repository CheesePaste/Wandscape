package com.wsteam.wandscape.core;

import com.wsteam.wandscape.core.component.Position;
import com.wsteam.wandscape.core.component.ManaPool;
import com.wsteam.wandscape.core.demo.MockBoundary;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.system.SystemBlueprintRegistry;
import com.wsteam.wandscape.core.task.BlueprintRegistry;
import com.wsteam.wandscape.core.types.GridPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

/**
 * Tests for World-level ECS operations added in V2.5:
 * {@link World#removeComponent(long, Class)}.
 *
 * <p>Note: EntityComponentBridge and NpcApiImpl are MC-adapter classes that
 * depend on {@code WandscapeNpc} ({@code PathfinderMob} subclass). They cannot
 * be unit-tested without Minecraft runtime; integration tests will use
 * {@code @GameTest} in a future stage.
 */
public class WorldEcsTest {

    @Nested
    class RemoveComponentTests {
        private World world;
        private long entity;

        @BeforeEach
        void setUp() {
            // Bootstrap a minimal world with Position and ManaPool stores
            MockBoundary mock = new MockBoundary();
            CoreBootstrapConfig config = new CoreBootstrapConfig(mock, mock, mock, null, mock,
                    java.util.List.of(), new BlueprintRegistry(),
                    new SystemBlueprintRegistry());
            world = CoreBootstrap.bootstrap(config);
            entity = world.createEntity();
        }

        @Test
        void removeComponent_removesFromStore() {
            world.addComponent(entity, Position.of(10, 64, 20));
            assertTrue(world.has(entity, Position.class));

            world.removeComponent(entity, Position.class);
            assertFalse(world.has(entity, Position.class),
                    "Position should be removed");
            assertNull(world.get(entity, Position.class),
                    "get should return null after remove");
        }

        @Test
        void removeComponent_noOpIfEntityDoesNotExist() {
            // Should not throw
            world.removeComponent(99999L, Position.class);
        }

        @Test
        void removeComponent_noOpIfStoreNotRegistered() {
            // GridPos class is not a component — no store for it
            assertDoesNotThrow(() ->
                    world.removeComponent(entity, GridPos.class));
        }

        @Test
        void removeComponent_onlyRemovesSpecifiedType() {
            world.addComponent(entity, Position.of(10, 64, 20));
            world.addComponent(entity, new ManaPool(100, 100, 5));

            world.removeComponent(entity, Position.class);

            assertFalse(world.has(entity, Position.class),
                    "Position should be removed");
            assertTrue(world.has(entity, ManaPool.class),
                    "ManaPool should still be present");
            assertNotNull(world.get(entity, ManaPool.class));
        }

        @Test
        void removeComponent_entityDisappearsFromQuery() {
            world.addComponent(entity, Position.of(10, 64, 20));
            world.addComponent(entity, new ManaPool(100, 100, 5));

            assertTrue(world.query(Position.class, ManaPool.class).contains(entity));

            world.removeComponent(entity, Position.class);
            assertFalse(world.query(Position.class, ManaPool.class).contains(entity),
                    "Entity should drop from intersection query after component removed");
        }
    }
}

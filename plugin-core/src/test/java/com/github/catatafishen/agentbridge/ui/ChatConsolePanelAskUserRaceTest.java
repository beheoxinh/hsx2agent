package com.github.catatafishen.agentbridge.ui;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

public class ChatConsolePanelAskUserRaceTest extends BasePlatformTestCase {

    private ChatConsolePanel panel;
    private Method rememberResolvedAskUser;
    private Constructor<?> activeAskUserCtor;
    private Field recentAskUserResolutionField;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        panel = new ChatConsolePanel(getProject(), false);
        Class<?> activeAskUserClass = Class.forName("com.github.catatafishen.agentbridge.ui.ChatConsolePanel$ActiveAskUser");
        activeAskUserCtor = activeAskUserClass.getDeclaredConstructor(
            String.class,
            Set.class,
            kotlin.jvm.functions.Function1.class,
            kotlin.jvm.functions.Function0.class,
            kotlin.jvm.functions.Function0.class
        );
        activeAskUserCtor.setAccessible(true);
        rememberResolvedAskUser = ChatConsolePanel.class.getDeclaredMethod("rememberResolvedAskUser", activeAskUserClass);
        rememberResolvedAskUser.setAccessible(true);
        recentAskUserResolutionField = ChatConsolePanel.class.getDeclaredField("recentAskUserResolution");
        recentAskUserResolutionField.setAccessible(true);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            panel.dispose();
        } finally {
            super.tearDown();
        }
    }

    public void testLegacyActiveAskUserConstructorRemainsAvailable() throws Exception {
        Constructor<?> legacyCtor = activeAskUserCtor.getDeclaringClass().getDeclaredConstructor(
                String.class,
                kotlin.jvm.functions.Function1.class,
                kotlin.jvm.functions.Function0.class,
                kotlin.jvm.functions.Function0.class
        );
        legacyCtor.setAccessible(true);
        Object activeAskUser = legacyCtor.newInstance(
                "req-legacy",
                new kotlin.jvm.functions.Function1<String, kotlin.Unit>() {
                    @Override
                    public kotlin.Unit invoke(String s) {
                        return kotlin.Unit.INSTANCE;
                    }
                },
                new kotlin.jvm.functions.Function0<Long>() {
                    @Override
                    public Long invoke() {
                        return 0L;
                    }
                },
                new kotlin.jvm.functions.Function0<kotlin.Unit>() {
                    @Override
                    public kotlin.Unit invoke() {
                        return kotlin.Unit.INSTANCE;
                    }
                }
        );

        assertNotNull(activeAskUser);
    }

    public void testLateAskUserReplyIsConsumedInsteadOfFallingThrough() throws Exception {
        Object activeAskUser = activeAskUserCtor.newInstance(
            "req-1",
            Set.of("yes, commit code", "no, cancel"),
            new kotlin.jvm.functions.Function1<String, kotlin.Unit>() {
                @Override
                public kotlin.Unit invoke(String s) {
                    return kotlin.Unit.INSTANCE;
                }
            },
            new kotlin.jvm.functions.Function0<Long>() {
                @Override
                public Long invoke() {
                    return 0L;
                }
            },
            new kotlin.jvm.functions.Function0<kotlin.Unit>() {
                @Override
                public kotlin.Unit invoke() {
                    return kotlin.Unit.INSTANCE;
                }
            }
        );

        rememberResolvedAskUser.invoke(panel, activeAskUser);

        assertTrue(panel.consumePendingAskUserResponse("Yes, commit code"));
        assertTrue(panel.consumePendingAskUserResponse("  No, cancel  "));
        assertFalse(panel.consumePendingAskUserResponse("ship it"));
    }

    public void testRememberResolvedAskUserExpiresStaleReplies() throws Exception {
        Object activeAskUser = activeAskUserCtor.newInstance(
            "req-2",
            Set.of("yes, commit code"),
            new kotlin.jvm.functions.Function1<String, kotlin.Unit>() {
                @Override
                public kotlin.Unit invoke(String s) {
                    return kotlin.Unit.INSTANCE;
                }
            },
            new kotlin.jvm.functions.Function0<Long>() {
                @Override
                public Long invoke() {
                    return 0L;
                }
            },
            new kotlin.jvm.functions.Function0<kotlin.Unit>() {
                @Override
                public kotlin.Unit invoke() {
                    return kotlin.Unit.INSTANCE;
                }
            }
        );

        rememberResolvedAskUser.invoke(panel, activeAskUser);
        Object resolution = recentAskUserResolutionField.get(panel);
        assertNotNull(resolution);

        Method copyMethod = resolution.getClass().getDeclaredMethod("copy", Set.class, long.class);
        copyMethod.setAccessible(true);
        Object expired = copyMethod.invoke(resolution, Set.of("yes, commit code"), System.currentTimeMillis() - 1);
        recentAskUserResolutionField.set(panel, expired);

        assertFalse(panel.consumePendingAskUserResponse("Yes, commit code"));
    }

    public void testRememberResolvedAskUserIgnoresBlankOptionSets() throws Exception {
        Object activeAskUser = activeAskUserCtor.newInstance(
            "req-3",
            Set.of(),
            new kotlin.jvm.functions.Function1<String, kotlin.Unit>() {
                @Override
                public kotlin.Unit invoke(String s) {
                    return kotlin.Unit.INSTANCE;
                }
            },
            new kotlin.jvm.functions.Function0<Long>() {
                @Override
                public Long invoke() {
                    return 0L;
                }
            },
            new kotlin.jvm.functions.Function0<kotlin.Unit>() {
                @Override
                public kotlin.Unit invoke() {
                    return kotlin.Unit.INSTANCE;
                }
            }
        );

        rememberResolvedAskUser.invoke(panel, activeAskUser);

        assertNull(recentAskUserResolutionField.get(panel));
        assertFalse(panel.consumePendingAskUserResponse("Yes, commit code"));
    }
}

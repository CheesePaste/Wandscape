package com.wsteam.wandscape.shared.data;

import java.util.Random;

/**
 * Shared random Chinese-style name pool used by both mages
 * ({@code WandscapeNpc}) and tourists ({@code TouristEntity}) so every named
 * character draws from one source. Kept in {@code shared/} because it is
 * visible to all modules without cross-module coupling.
 */
public final class CharacterNames {

    private static final String[] SURNAMES = {
        "王","李","张","刘","陈","杨","赵","黄","周","吴",
        "游客","旅人","行者","访客","商贾"
    };
    private static final String[] GIVENS = {
        "明","华","文","伟","芳","丽","强","勇","静",
        "慧","敏","俊","杰","兰","玲","超","平","刚","涛"
    };

    private static final Random RANDOM = new Random();

    private CharacterNames() {}

    /** Generate a random name (surname + given, no space). */
    public static String generateRandomName() {
        return SURNAMES[RANDOM.nextInt(SURNAMES.length)]
                + GIVENS[RANDOM.nextInt(GIVENS.length)];
    }
}

package com.wsteam.wandscape.foundation.util;

/**
 * Naming rule for generated tourist/NPC names, switchable per colony in the
 * town hall UI. Only affects names generated after the switch — existing
 * entities keep their stored names.
 */
public enum NameStyle {
    /** Western fantasy: Latin/Roman single given name (Marcus, Aurelia). Default. */
    FANTASY,
    /** Chinese: surname + given name (王明 / Wang Ming). */
    CHINESE,
    /** English: given + surname (John Smith / 约翰·史密斯). */
    ENGLISH
}

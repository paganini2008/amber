package com.github.amber.dialect.split;

/**
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public enum UnitKind {
    /** A SQL statement, PL block or batch that is parsed by the dialect grammar. */
    SQL,
    /** A client-tool command such as SQL*Plus {@code SET}/{@code PROMPT}, sqlcmd {@code :setvar} or psql {@code \set}. */
    CLIENT_COMMAND,
    /** Inline data, such as the rows following {@code COPY … FROM STDIN}. */
    DATA
}

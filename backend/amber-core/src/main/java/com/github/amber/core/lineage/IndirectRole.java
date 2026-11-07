package com.github.amber.core.lineage;

/**
 * Why a column influences a result without flowing into it: it filters, joins or groups the rows.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public enum IndirectRole {
    FILTER,
    JOIN,
    GROUP
}

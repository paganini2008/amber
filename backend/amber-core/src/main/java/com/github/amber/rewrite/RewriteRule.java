package com.github.amber.rewrite;

import java.util.List;

/**
 * One rewrite rule: looks at a statement and proposes candidate rewrites. Rules only propose edits whose
 * correctness they can argue from the tokens and the known metadata; the suggester verifies them.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
interface RewriteRule {

    /** The finding id the rule fixes, e.g. {@code AMB-S002}. */
    String id();

    List<Candidate> propose(RewriteContext ctx);
}

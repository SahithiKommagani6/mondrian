/*
// $Id$
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// Copyright (C) 2006-2011 Julian Hyde
// All Rights Reserved.
// You must accept the terms of that agreement to use this software.
*/
package mondrian.olap.fun;

import mondrian.calc.*;
import mondrian.calc.impl.AbstractListCalc;
import mondrian.calc.impl.ConstantCalc;
import mondrian.mdx.ResolvedFunCall;
import mondrian.olap.Evaluator;
import mondrian.olap.FunDef;

/**
 * Definition of the <code>Head</code> and <code>Tail</code>
 * MDX builtin functions.
 *
 * @author jhyde
 * @version $Id$
 * @since Mar 23, 2006
 */
class HeadTailFunDef extends FunDefBase {
    static final Resolver TailResolver =
        new ReflectiveMultiResolver(
            "Tail",
            "Tail(<Set>[, <Count>])",
            "Returns a subset from the end of a set.",
            new String[] {"fxx", "fxxn"},
            HeadTailFunDef.class);

    static final Resolver HeadResolver =
        new ReflectiveMultiResolver(
            "Head",
            "Head(<Set>[, < Numeric Expression >])",
            "Returns the first specified number of elements in a set.",
            new String[] {"fxx", "fxxn"},
            HeadTailFunDef.class);

    private final boolean head;

    public HeadTailFunDef(FunDef dummyFunDef) {
        super(dummyFunDef);
        head = dummyFunDef.getName().equals("Head");
    }

    public Calc compileCall(ResolvedFunCall call, ExpCompiler compiler) {
        final ListCalc listCalc =
            compiler.compileList(call.getArg(0));
        final IntegerCalc integerCalc =
            call.getArgCount() > 1
            ? compiler.compileInteger(call.getArg(1))
            : ConstantCalc.constantInteger(1);
        if (head) {
            return new AbstractListCalc(
                call, new Calc[] {listCalc, integerCalc})
            {
                public TupleList evaluateList(Evaluator evaluator) {
                    final int savepoint = evaluator.savepoint();
                    evaluator.setNonEmpty(false);
                    TupleList list = listCalc.evaluateList(evaluator);
                    int count = integerCalc.evaluateInteger(evaluator);
                    evaluator.restore(savepoint);
                    return head(count, list);
                }
            };
        } else {
            return new AbstractListCalc(
                call, new Calc[] {listCalc, integerCalc})
            {
                public TupleList evaluateList(Evaluator evaluator) {
                    final int savepoint = evaluator.savepoint();
                    evaluator.setNonEmpty(false);
                    TupleList list = listCalc.evaluateList(evaluator);
                    int count = integerCalc.evaluateInteger(evaluator);
                    evaluator.restore(savepoint);
                    return tail(count, list);
                }
            };
        }
    }

    static TupleList tail(final int count, final TupleList members) {
        assert members != null;
        final int memberCount = members.size();
        if (count >= memberCount) {
            return members;
        }
        if (count <= 0) {
            return TupleCollections.emptyList(members.getArity());
        }
        return members.subList(members.size() - count, members.size());
    }

    static TupleList head(final int count, final TupleList members) {
        assert members != null;
        if (count <= 0) {
            return TupleCollections.emptyList(members.getArity());
        }
        return members.subList(0, Math.min(count, members.size()));
    }
}

// End HeadTailFunDef.java
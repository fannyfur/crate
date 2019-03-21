/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.expression.scalar;

import io.crate.expression.symbol.Literal;
import io.crate.types.ArrayType;
import io.crate.types.DataTypes;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsSame;
import org.junit.Test;

import static io.crate.testing.SymbolMatchers.isLiteral;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

public class ArrayDifferenceFunctionTest extends AbstractScalarFunctionsTest {

    private static final ArrayType INTEGER_ARRAY_TYPE = new ArrayType(DataTypes.INTEGER);

    @Test
    public void testCompileWithValues() throws Exception {
        assertCompile("array_difference(int_array, [3, 4, 5])", (s) -> not(IsSame.sameInstance(s)));
    }

    @Test
    public void testCompileWithRefs() throws Exception {
        assertCompile("array_difference(int_array, int_array)", IsSame::sameInstance);
    }

    @Test
    public void testArrayDifferenceRemovesTheNestedArraysInTheFirstArrayThatAreContainedWithinTheSecondArray() {
        assertEvaluate(
            "array_difference([[1, 2], [1, 3]], [[1, 2]])",
            Matchers.arrayContaining(Matchers.arrayContaining(1L, 3L)));
    }

    @Test
    public void testArrayDifferenceRemovesTheObjectsInTheFirstArrayThatAreContainedInTheSecond() {
        assertEvaluate(
            "array_difference([{x=[1, 2]}, {x=[1, 3]}], [{x=[1, 3]}])",
            Matchers.arrayContaining(Matchers.hasEntry(is("x"), Matchers.arrayContaining(1L, 2L))));
    }

    @Test
    public void testNormalize() throws Exception {
        assertNormalize("array_difference([10, 20], [10, 30])", isLiteral(new Long[]{20L}));
        assertNormalize("array_difference([], [10, 30])", isLiteral(new Object[0]));
    }

    @Test
    public void testNormalizeNullArguments() throws Exception {
        assertNormalize("array_difference([1], null)", isLiteral(new Object[] {1L}));
    }

    @Test
    public void testEvaluateNullArguments() throws Exception {
        assertEvaluate("array_difference([1], long_array)", new Object[]{1L}, Literal.NULL);
        assertEvaluate("array_difference(long_array, [1])", null, Literal.NULL);
    }

    @Test
    public void testZeroArguments() throws Exception {
        expectedException.expect(UnsupportedOperationException.class);
        expectedException.expectMessage("unknown function: array_difference()");
        assertNormalize("array_difference()", null);
    }

    @Test
    public void testOneArgument() {
        expectedException.expect(UnsupportedOperationException.class);
        expectedException.expectMessage("unknown function: array_difference(bigint_array)");
        assertNormalize("array_difference([1])", null);
    }

    @Test
    public void testDifferentBuConvertableInnerTypes() throws Exception {
        assertEvaluate("array_difference([1::integer], [1::long])", new Object[0]);
    }

    @Test
    public void testNullElements() throws Exception {
        assertEvaluate("array_difference(int_array, int_array)",
            new Object[]{1},
            Literal.of(new Object[]{1, null, 3}, INTEGER_ARRAY_TYPE),
            Literal.of(new Object[]{null, 2, 3}, INTEGER_ARRAY_TYPE));
        assertEvaluate("array_difference(int_array, int_array)",
            new Object[]{1, null, 2, null},
            Literal.of(new Object[]{1, null, 3, 2, null}, INTEGER_ARRAY_TYPE),
            Literal.of(new Object[]{3}, INTEGER_ARRAY_TYPE));
    }

    @Test
    public void testEmptyArrays() throws Exception {
        expectedException.expect(IllegalArgumentException.class);
        expectedException.expectMessage("One of the arguments of the array_difference function can be of undefined inner type, but not both");
        assertNormalize("array_difference([], [])", null);
    }
}

/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.gen;

import com.facebook.presto.operator.DriverYieldSignal;
import com.facebook.presto.operator.Work;
import com.facebook.presto.operator.project.PageProjection;
import com.facebook.presto.operator.project.SelectedPositions;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.relation.CallExpression;
import org.testng.annotations.Test;

import java.util.Optional;
import java.util.function.Supplier;

import static com.facebook.presto.metadata.MetadataManager.createTestMetadataManager;
import static com.facebook.presto.spi.StandardErrorCode.NUMERIC_VALUE_OUT_OF_RANGE;
import static com.facebook.presto.spi.function.OperatorType.ADD;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.sql.analyzer.TypeSignatureProvider.fromTypes;
import static com.facebook.presto.sql.relational.Expressions.call;
import static com.facebook.presto.sql.relational.Expressions.constant;
import static com.facebook.presto.sql.relational.Expressions.field;
import static com.facebook.presto.testing.TestingConnectorSession.SESSION;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public class TestPageFunctionCompiler
{
    private static final CallExpression ADD_10_EXPRESSION = call(
            ADD.name(),
            createTestMetadataManager().getFunctionManager().resolveOperator(ADD, fromTypes(BIGINT, BIGINT)),
            BIGINT,
            field(0, BIGINT),
            constant(10L, BIGINT));

    @Test
    public void testFailureDoesNotCorruptFutureResults()
    {
        PageFunctionCompiler functionCompiler = new PageFunctionCompiler(createTestMetadataManager(), 0);

        Supplier<PageProjection> projectionSupplier = functionCompiler.compileProjection(SESSION.getSqlFunctionProperties(), ADD_10_EXPRESSION, Optional.empty());
        PageProjection projection = projectionSupplier.get();

        // process good page and verify we got the expected number of result rows
        Page goodPage = createLongBlockPage(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        Block goodResult = project(projection, goodPage, SelectedPositions.positionsRange(0, goodPage.getPositionCount()));
        assertEquals(goodPage.getPositionCount(), goodResult.getPositionCount());

        // addition will throw due to integer overflow
        Page badPage = createLongBlockPage(0, 1, 2, 3, 4, Long.MAX_VALUE);
        try {
            project(projection, badPage, SelectedPositions.positionsRange(0, 100));
            fail("expected exception");
        }
        catch (PrestoException e) {
            assertEquals(e.getErrorCode(), NUMERIC_VALUE_OUT_OF_RANGE.toErrorCode());
        }

        // running the good page should still work
        // if block builder in generated code was not reset properly, we could get junk results after the failure
        goodResult = project(projection, goodPage, SelectedPositions.positionsRange(0, goodPage.getPositionCount()));
        assertEquals(goodPage.getPositionCount(), goodResult.getPositionCount());
    }

    @Test
    public void testGeneratedClassName()
    {
        PageFunctionCompiler functionCompiler = new PageFunctionCompiler(createTestMetadataManager(), 0);

        String planNodeId = "7";
        String stageId = "20170707_223500_67496_zguwn.2";
        String classSuffix = stageId + "_" + planNodeId;
        Supplier<PageProjection> projectionSupplier = functionCompiler.compileProjection(SESSION.getSqlFunctionProperties(), ADD_10_EXPRESSION, Optional.of(classSuffix));
        PageProjection projection = projectionSupplier.get();
        Work<Block> work = projection.project(SESSION.getSqlFunctionProperties(), new DriverYieldSignal(), createLongBlockPage(0), SelectedPositions.positionsRange(0, 1));
        // class name should look like PageProjectionOutput_20170707_223500_67496_zguwn_2_7_XX
        assertTrue(work.getClass().getSimpleName().startsWith("PageProjectionWork_" + stageId.replace('.', '_') + "_" + planNodeId));
    }

    @Test
    public void testCache()
    {
        PageFunctionCompiler cacheCompiler = new PageFunctionCompiler(createTestMetadataManager(), 100);
        assertSame(
                cacheCompiler.compileProjection(SESSION.getSqlFunctionProperties(), ADD_10_EXPRESSION, Optional.empty()),
                cacheCompiler.compileProjection(SESSION.getSqlFunctionProperties(), ADD_10_EXPRESSION, Optional.empty()));
        assertSame(
                cacheCompiler.compileProjection(SESSION.getSqlFunctionProperties(), ADD_10_EXPRESSION, Optional.of("hint")),
                cacheCompiler.compileProjection(SESSION.getSqlFunctionProperties(), ADD_10_EXPRESSION, Optional.of("hint")));
        assertSame(
                cacheCompiler.compileProjection(SESSION.getSqlFunctionProperties(), ADD_10_EXPRESSION, Optional.of("hint")),
                cacheCompiler.compileProjection(SESSION.getSqlFunctionProperties(), ADD_10_EXPRESSION, Optional.of("hint2")));
        assertSame(
                cacheCompiler.compileProjection(SESSION.getSqlFunctionProperties(), ADD_10_EXPRESSION, Optional.empty()),
                cacheCompiler.compileProjection(SESSION.getSqlFunctionProperties(), ADD_10_EXPRESSION, Optional.of("hint2")));

        PageFunctionCompiler noCacheCompiler = new PageFunctionCompiler(createTestMetadataManager(), 0);
        assertNotSame(
                noCacheCompiler.compileProjection(SESSION.getSqlFunctionProperties(), ADD_10_EXPRESSION, Optional.empty()),
                noCacheCompiler.compileProjection(SESSION.getSqlFunctionProperties(), ADD_10_EXPRESSION, Optional.empty()));
        assertNotSame(
                noCacheCompiler.compileProjection(SESSION.getSqlFunctionProperties(), ADD_10_EXPRESSION, Optional.of("hint")),
                noCacheCompiler.compileProjection(SESSION.getSqlFunctionProperties(), ADD_10_EXPRESSION, Optional.of("hint")));
        assertNotSame(
                noCacheCompiler.compileProjection(SESSION.getSqlFunctionProperties(), ADD_10_EXPRESSION, Optional.of("hint")),
                noCacheCompiler.compileProjection(SESSION.getSqlFunctionProperties(), ADD_10_EXPRESSION, Optional.of("hint2")));
        assertNotSame(
                noCacheCompiler.compileProjection(SESSION.getSqlFunctionProperties(), ADD_10_EXPRESSION, Optional.empty()),
                noCacheCompiler.compileProjection(SESSION.getSqlFunctionProperties(), ADD_10_EXPRESSION, Optional.of("hint2")));
    }

    private Block project(PageProjection projection, Page page, SelectedPositions selectedPositions)
    {
        Work<Block> work = projection.project(SESSION.getSqlFunctionProperties(), new DriverYieldSignal(), page, selectedPositions);
        assertTrue(work.process());
        return work.getResult();
    }

    private static Page createLongBlockPage(long... values)
    {
        BlockBuilder builder = BIGINT.createFixedSizeBlockBuilder(values.length);
        for (long value : values) {
            BIGINT.writeLong(builder, value);
        }
        return new Page(builder.build());
    }
}

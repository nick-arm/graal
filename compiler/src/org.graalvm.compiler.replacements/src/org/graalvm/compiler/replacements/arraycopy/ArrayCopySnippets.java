/*
 * Copyright (c) 2011, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.compiler.replacements.arraycopy;

import static jdk.vm.ci.services.Services.IS_BUILDING_NATIVE_IMAGE;
import static org.graalvm.compiler.core.common.GraalOptions.GeneratePIC;
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.FREQUENT_PROBABILITY;
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.LIKELY_PROBABILITY;
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.NOT_FREQUENT_PROBABILITY;
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.SLOW_PATH_PROBABILITY;
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.DEOPT_PROBABILITY;
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.probability;

import java.util.EnumMap;

import org.graalvm.collections.UnmodifiableEconomicMap;
import org.graalvm.compiler.api.directives.GraalDirectives;
import org.graalvm.compiler.api.replacements.Fold.InjectedParameter;
import org.graalvm.compiler.api.replacements.Snippet;
import org.graalvm.compiler.api.replacements.Snippet.ConstantParameter;
import org.graalvm.compiler.api.replacements.SnippetReflectionProvider;
import org.graalvm.compiler.debug.DebugHandlersFactory;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.CallTargetNode;
import org.graalvm.compiler.nodes.DeoptimizeNode;
import org.graalvm.compiler.nodes.InvokeNode;
import org.graalvm.compiler.nodes.InvokeWithExceptionNode;
import org.graalvm.compiler.nodes.NamedLocationIdentity;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.PiNode;
import org.graalvm.compiler.nodes.SnippetAnchorNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.extended.GuardedUnsafeLoadNode;
import org.graalvm.compiler.nodes.extended.GuardingNode;
import org.graalvm.compiler.nodes.extended.RawStoreNode;
import org.graalvm.compiler.nodes.java.ArrayLengthNode;
import org.graalvm.compiler.nodes.spi.LoweringTool;
import org.graalvm.compiler.nodes.type.StampTool;
import org.graalvm.compiler.nodes.util.GraphUtil;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.phases.util.Providers;
import org.graalvm.compiler.replacements.ReplacementsUtil;
import org.graalvm.compiler.replacements.SnippetCounter;
import org.graalvm.compiler.replacements.SnippetCounter.Group;
import org.graalvm.compiler.replacements.SnippetCounter.Group.Factory;
import org.graalvm.compiler.replacements.SnippetIntegerHistogram;
import org.graalvm.compiler.replacements.SnippetTemplate;
import org.graalvm.compiler.replacements.SnippetTemplate.Arguments;
import org.graalvm.compiler.replacements.SnippetTemplate.SnippetInfo;
import org.graalvm.compiler.replacements.Snippets;
import org.graalvm.compiler.replacements.nodes.BasicArrayCopyNode;
import org.graalvm.compiler.word.Word;
import org.graalvm.word.LocationIdentity;
import org.graalvm.word.Pointer;

import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.meta.DeoptimizationAction;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

public abstract class ArrayCopySnippets implements Snippets {

    private enum ArrayCopyTypeCheck {
        UNDEFINED_ARRAY_TYPE_CHECK,
        // either we know that both objects are arrays and have the same type,
        // or we apply generic array copy snippet, which enforces type check
        NO_ARRAY_TYPE_CHECK,
        // can be used when we know that one of the objects is a primitive array
        HUB_BASED_ARRAY_TYPE_CHECK,
        // can be used when we know that one of the objects is an object array
        LAYOUT_HELPER_BASED_ARRAY_TYPE_CHECK
    }

    /** Marker value for the {@link InjectedParameter} injected parameter. */
    static final MetaAccessProvider INJECTED_META_ACCESS = null;

    public abstract Pointer loadHub(Object nonNullSrc);

    public abstract Pointer getDestElemClass(Pointer destKlass);

    public abstract Word getSuperCheckOffset(Pointer destElemKlass);

    public abstract int getReadLayoutHelper(Pointer srcHub);

    protected abstract int heapWordSize();

    @SuppressWarnings("unused")
    @Snippet
    public void arraycopyExactSnippet(Object src, int srcPos, Object dest, int destPos, int length, @ConstantParameter ArrayCopyTypeCheck arrayTypeCheck,
                    @ConstantParameter SnippetInfo workSnippet, @ConstantParameter JavaKind elementKind, @ConstantParameter LocationIdentity locationIdentity,
                    @ConstantParameter SnippetCounter elementKindCounter, @ConstantParameter SnippetCounter elementKindCopiedCounter, @ConstantParameter Counters counters) {
        Object nonNullSrc = GraalDirectives.guardingNonNull(src);
        Object nonNullDest = GraalDirectives.guardingNonNull(dest);
        checkArrayTypes(nonNullSrc, nonNullDest, arrayTypeCheck);
        checkLimits(nonNullSrc, srcPos, nonNullDest, destPos, length, counters);
        incrementLengthCounter(length, counters);

        elementKindCounter.inc();
        elementKindCopiedCounter.add(length);

        ArrayCopyWithDelayedLoweringNode.arraycopy(nonNullSrc, srcPos, nonNullDest, destPos, length, workSnippet, elementKind);
    }

    @SuppressWarnings("unused")
    @Snippet
    public void arraycopyExactStubCallSnippet(Object src, int srcPos, Object dest, int destPos, int length, @ConstantParameter ArrayCopyTypeCheck arrayTypeCheck,
                    @ConstantParameter SnippetInfo workSnippet, @ConstantParameter JavaKind elementKind, @ConstantParameter LocationIdentity locationIdentity,
                    @ConstantParameter SnippetCounter elementKindCounter, @ConstantParameter SnippetCounter elementKindCopiedCounter, @ConstantParameter Counters counters) {
        Object nonNullSrc = GraalDirectives.guardingNonNull(src);
        Object nonNullDest = GraalDirectives.guardingNonNull(dest);
        checkArrayTypes(nonNullSrc, nonNullDest, arrayTypeCheck);
        checkLimits(nonNullSrc, srcPos, nonNullDest, destPos, length, counters);
        incrementLengthCounter(length, counters);

        elementKindCounter.inc();
        elementKindCopiedCounter.add(length);

        ArrayCopyCallNode.arraycopy(nonNullSrc, srcPos, nonNullDest, destPos, length, elementKind, locationIdentity, heapWordSize());
    }

    @Snippet
    public void arraycopyCheckcastSnippet(Object src, int srcPos, Object dest, int destPos, int length, @ConstantParameter ArrayCopyTypeCheck arrayTypeCheck, @ConstantParameter Counters counters,
                    @ConstantParameter SnippetInfo workSnippet, @ConstantParameter JavaKind elementKind) {
        Object nonNullSrc = GraalDirectives.guardingNonNull(src);
        Object nonNullDest = GraalDirectives.guardingNonNull(dest);
        checkArrayTypes(nonNullSrc, nonNullDest, arrayTypeCheck);
        checkLimits(nonNullSrc, srcPos, nonNullDest, destPos, length, counters);
        incrementLengthCounter(length, counters);

        ArrayCopyWithDelayedLoweringNode.arraycopy(nonNullSrc, srcPos, nonNullDest, destPos, length, workSnippet, elementKind);
    }

    @Snippet
    public void arraycopyGenericSnippet(Object src, int srcPos, Object dest, int destPos, int length, @ConstantParameter ArrayCopyTypeCheck arrayTypeCheck, @ConstantParameter Counters counters,
                    @ConstantParameter SnippetInfo workSnippet, @ConstantParameter JavaKind elementKind) {
        Object nonNullSrc = GraalDirectives.guardingNonNull(src);
        Object nonNullDest = GraalDirectives.guardingNonNull(dest);
        checkArrayTypes(nonNullSrc, nonNullDest, arrayTypeCheck);
        checkLimits(nonNullSrc, srcPos, nonNullDest, destPos, length, counters);
        incrementLengthCounter(length, counters);

        ArrayCopyWithDelayedLoweringNode.arraycopy(nonNullSrc, srcPos, nonNullDest, destPos, length, workSnippet, elementKind);
    }

    @Snippet
    public static void arraycopyNativeSnippet(Object src, int srcPos, Object dest, int destPos, int length, @ConstantParameter Counters counters) {
        // all checks are done in the native method, so no need to emit additional checks here
        incrementLengthCounter(length, counters);
        counters.systemArraycopyCounter.inc();
        counters.systemArraycopyCopiedCounter.add(length);

        System.arraycopy(src, srcPos, dest, destPos, length);
    }

    @SuppressWarnings("unused")
    @Snippet(allowPartialIntrinsicArgumentMismatch = true)
    public void exactArraycopyWithSlowPathWork(Object src, int srcPos, Object dest, int destPos, int length, @ConstantParameter JavaKind elementKind, @ConstantParameter LocationIdentity arrayLocation,
                    @ConstantParameter Counters counters) {
        int scale = ReplacementsUtil.arrayIndexScale(INJECTED_META_ACCESS, elementKind);
        int arrayBaseOffset = ReplacementsUtil.getArrayBaseOffset(INJECTED_META_ACCESS, elementKind);
        long sourceOffset = arrayBaseOffset + (long) srcPos * scale;
        long destOffset = arrayBaseOffset + (long) destPos * scale;

        GuardingNode anchor = SnippetAnchorNode.anchor();
        if (probability(NOT_FREQUENT_PROBABILITY, src == dest && srcPos < destPos)) {
            // bad aliased case so we need to copy the array from back to front
            for (int position = length - 1; position >= 0; position--) {
                Object value = GuardedUnsafeLoadNode.guardedLoad(src, sourceOffset + ((long) position) * scale, elementKind, arrayLocation, anchor);
                RawStoreNode.storeObject(dest, destOffset + ((long) position) * scale, value, elementKind, arrayLocation, true);
            }
        } else {
            for (int position = 0; position < length; position++) {
                Object value = GuardedUnsafeLoadNode.guardedLoad(src, sourceOffset + ((long) position) * scale, elementKind, arrayLocation, anchor);
                RawStoreNode.storeObject(dest, destOffset + ((long) position) * scale, value, elementKind, arrayLocation, true);
            }
        }
    }

    @SuppressWarnings("unused")
    @Snippet(allowPartialIntrinsicArgumentMismatch = true)
    public void checkcastArraycopyWithSlowPathWork(Object src, int srcPos, Object dest, int destPos, int length, @ConstantParameter JavaKind elementKind,
                    @ConstantParameter LocationIdentity arrayLocation,
                    @ConstantParameter Counters counters) {
        if (probability(FREQUENT_PROBABILITY, length > 0)) {
            Object nonNullSrc = PiNode.asNonNullObject(src);
            Object nonNullDest = PiNode.asNonNullObject(dest);
            Pointer srcKlass = loadHub(nonNullSrc);
            Pointer destKlass = loadHub(nonNullDest);
            if (probability(LIKELY_PROBABILITY, srcKlass == destKlass) || probability(LIKELY_PROBABILITY, nonNullDest.getClass() == Object[].class)) {
                // no storecheck required.
                counters.objectCheckcastSameTypeCounter.inc();
                counters.objectCheckcastSameTypeCopiedCounter.add(length);
                ArrayCopyCallNode.arraycopyObjectKillsAny(nonNullSrc, srcPos, nonNullDest, destPos, length, heapWordSize());
            } else {
                Pointer destElemKlass = getDestElemClass(destKlass);
                Word superCheckOffset = getSuperCheckOffset(destElemKlass);

                counters.objectCheckcastDifferentTypeCounter.inc();
                counters.objectCheckcastDifferentTypeCopiedCounter.add(length);
                int copiedElements = CheckcastArrayCopyCallNode.checkcastArraycopy(nonNullSrc, srcPos, nonNullDest, destPos, length, superCheckOffset, destElemKlass, false);
                if (probability(SLOW_PATH_PROBABILITY, copiedElements != 0)) {
                    /*
                     * the stub doesn't throw the ArrayStoreException, but returns the number of
                     * copied elements (xor'd with -1).
                     */
                    copiedElements ^= -1;
                    System.arraycopy(nonNullSrc, srcPos + copiedElements, nonNullDest, destPos + copiedElements, length - copiedElements);
                }
            }
        }
    }

    @SuppressWarnings("unused")
    @Snippet(allowPartialIntrinsicArgumentMismatch = true)
    public void genericArraycopyWithSlowPathWork(Object src, int srcPos, Object dest, int destPos, int length, @ConstantParameter JavaKind elementKind,
                    @ConstantParameter LocationIdentity arrayLocation,
                    @ConstantParameter Counters counters) {
        // The length > 0 check should not be placed here because generic array copy stub should
        // enforce type check. This is fine performance-wise because this snippet is rarely used.
        counters.genericArraycopyDifferentTypeCounter.inc();
        counters.genericArraycopyDifferentTypeCopiedCounter.add(length);
        int copiedElements = GenericArrayCopyCallNode.genericArraycopy(src, srcPos, dest, destPos, length);
        if (probability(SLOW_PATH_PROBABILITY, copiedElements != 0)) {
            /*
             * the stub doesn't throw the ArrayStoreException, but returns the number of copied
             * elements (xor'd with -1).
             */
            copiedElements ^= -1;
            System.arraycopy(src, srcPos + copiedElements, dest, destPos + copiedElements, length - copiedElements);
        }
    }

    private static void incrementLengthCounter(int length, Counters counters) {
        if (!IS_BUILDING_NATIVE_IMAGE) {
            counters.lengthHistogram.inc(length);
        }
    }

    /**
     * Writing this as individual if statements to avoid a merge without a frame state.
     */
    private static void checkLimits(Object src, int srcPos, Object dest, int destPos, int length, Counters counters) {
        if (probability(DEOPT_PROBABILITY, srcPos < 0)) {
            counters.checkAIOOBECounter.inc();
            DeoptimizeNode.deopt(DeoptimizationAction.None, DeoptimizationReason.RuntimeConstraint);
        }
        if (probability(DEOPT_PROBABILITY, destPos < 0)) {
            counters.checkAIOOBECounter.inc();
            DeoptimizeNode.deopt(DeoptimizationAction.None, DeoptimizationReason.RuntimeConstraint);
        }
        if (probability(DEOPT_PROBABILITY, length < 0)) {
            counters.checkAIOOBECounter.inc();
            DeoptimizeNode.deopt(DeoptimizationAction.None, DeoptimizationReason.RuntimeConstraint);
        }
        if (probability(DEOPT_PROBABILITY, srcPos > ArrayLengthNode.arrayLength(src) - length)) {
            counters.checkAIOOBECounter.inc();
            DeoptimizeNode.deopt(DeoptimizationAction.None, DeoptimizationReason.RuntimeConstraint);
        }
        if (probability(DEOPT_PROBABILITY, destPos > ArrayLengthNode.arrayLength(dest) - length)) {
            counters.checkAIOOBECounter.inc();
            DeoptimizeNode.deopt(DeoptimizationAction.None, DeoptimizationReason.RuntimeConstraint);
        }
        counters.checkSuccessCounter.inc();
    }

    private void checkArrayTypes(Object nonNullSrc, Object nonNullDest, ArrayCopyTypeCheck arrayTypeCheck) {
        if (arrayTypeCheck == ArrayCopyTypeCheck.NO_ARRAY_TYPE_CHECK) {
            // nothing to do
        } else if (arrayTypeCheck == ArrayCopyTypeCheck.HUB_BASED_ARRAY_TYPE_CHECK) {
            Pointer srcHub = loadHub(nonNullSrc);
            Pointer destHub = loadHub(nonNullDest);
            if (probability(DEOPT_PROBABILITY, srcHub != destHub)) {
                DeoptimizeNode.deopt(DeoptimizationAction.None, DeoptimizationReason.RuntimeConstraint);
            }
        } else if (arrayTypeCheck == ArrayCopyTypeCheck.LAYOUT_HELPER_BASED_ARRAY_TYPE_CHECK) {
            Pointer srcHub = loadHub(nonNullSrc);
            Pointer destHub = loadHub(nonNullDest);
            if (probability(DEOPT_PROBABILITY, getReadLayoutHelper(srcHub) != getReadLayoutHelper(destHub))) {
                DeoptimizeNode.deopt(DeoptimizationAction.None, DeoptimizationReason.RuntimeConstraint);
            }
        } else {
            ReplacementsUtil.staticAssert(false, "unknown array type check ", arrayTypeCheck);
        }
    }

    static class Counters {
        final SnippetCounter checkSuccessCounter;
        final SnippetCounter checkAIOOBECounter;

        final SnippetCounter zeroLengthStaticCounter;
        final SnippetIntegerHistogram lengthHistogram;

        final SnippetCounter systemArraycopyCounter;
        final SnippetCounter systemArraycopyCopiedCounter;

        final SnippetCounter genericArraycopyDifferentTypeCopiedCounter;
        final SnippetCounter genericArraycopyDifferentTypeCounter;

        final SnippetCounter objectCheckcastSameTypeCopiedCounter;
        final SnippetCounter objectCheckcastSameTypeCounter;
        final SnippetCounter objectCheckcastDifferentTypeCopiedCounter;
        final SnippetCounter objectCheckcastDifferentTypeCounter;

        final EnumMap<JavaKind, SnippetCounter> arraycopyCallCounters = new EnumMap<>(JavaKind.class);
        final EnumMap<JavaKind, SnippetCounter> arraycopyCallCopiedCounters = new EnumMap<>(JavaKind.class);

        Counters(SnippetCounter.Group.Factory factory) {
            final Group checkCounters = factory.createSnippetCounterGroup("System.arraycopy checkInputs");
            final Group callCounters = factory.createSnippetCounterGroup("System.arraycopy calls");
            final Group copiedElementsCounters = factory.createSnippetCounterGroup("System.arraycopy copied elements");
            final Group lengthCounters = factory.createSnippetCounterGroup("System.arraycopy with 0-length");

            checkSuccessCounter = new SnippetCounter(checkCounters, "checkSuccess", "checkSuccess");
            checkAIOOBECounter = new SnippetCounter(checkCounters, "checkAIOOBE", "checkAIOOBE");

            zeroLengthStaticCounter = new SnippetCounter(lengthCounters, "0-length copy static", "calls where the length is statically 0");
            lengthHistogram = new SnippetIntegerHistogram(lengthCounters, 2, "length", "length");

            systemArraycopyCounter = new SnippetCounter(callCounters, "native System.arraycopy", "JNI-based System.arraycopy call");
            systemArraycopyCopiedCounter = new SnippetCounter(copiedElementsCounters, "native System.arraycopy", "JNI-based System.arraycopy call");

            genericArraycopyDifferentTypeCounter = new SnippetCounter(callCounters, "generic[] stub", "generic arraycopy stub");
            genericArraycopyDifferentTypeCopiedCounter = new SnippetCounter(copiedElementsCounters, "generic[] stub", "generic arraycopy stub");

            objectCheckcastSameTypeCounter = new SnippetCounter(callCounters, "checkcast object[] (same-type)", "checkcast object[] stub but src.klass == dest.klass Object[] arrays");
            objectCheckcastSameTypeCopiedCounter = new SnippetCounter(copiedElementsCounters, "checkcast object[] (same-type)", "checkcast object[] stub but src.klass == dest.klass Object[] arrays");
            objectCheckcastDifferentTypeCounter = new SnippetCounter(callCounters, "checkcast object[] (store-check)", "checkcast object[] stub with store check");
            objectCheckcastDifferentTypeCopiedCounter = new SnippetCounter(copiedElementsCounters, "checkcast object[] (store-check)", "checkcast object[] stub with store check");

            createArraycopyCounter(JavaKind.Byte, callCounters, copiedElementsCounters);
            createArraycopyCounter(JavaKind.Boolean, callCounters, copiedElementsCounters);
            createArraycopyCounter(JavaKind.Char, callCounters, copiedElementsCounters);
            createArraycopyCounter(JavaKind.Short, callCounters, copiedElementsCounters);
            createArraycopyCounter(JavaKind.Int, callCounters, copiedElementsCounters);
            createArraycopyCounter(JavaKind.Long, callCounters, copiedElementsCounters);
            createArraycopyCounter(JavaKind.Float, callCounters, copiedElementsCounters);
            createArraycopyCounter(JavaKind.Double, callCounters, copiedElementsCounters);
            createArraycopyCounter(JavaKind.Object, callCounters, copiedElementsCounters);
        }

        void createArraycopyCounter(JavaKind kind, Group counters, Group copiedCounters) {
            arraycopyCallCounters.put(kind, new SnippetCounter(counters, kind + "[] stub", "arraycopy call for " + kind + "[] arrays"));
            arraycopyCallCopiedCounters.put(kind, new SnippetCounter(copiedCounters, kind + "[] stub", "arraycopy call for " + kind + "[] arrays"));
        }
    }

    public static class Templates extends SnippetTemplate.AbstractTemplates {
        private final SnippetInfo arraycopyGenericSnippet;
        private final SnippetInfo arraycopyExactSnippet;
        private final SnippetInfo arraycopyExactStubCallSnippet;
        private final SnippetInfo arraycopyCheckcastSnippet;
        private final SnippetInfo arraycopyNativeSnippet;
        private final SnippetInfo checkcastArraycopyWithSlowPathWork;
        private final SnippetInfo genericArraycopyWithSlowPathWork;
        private final SnippetInfo exactArraycopyWithSlowPathWork;

        private ResolvedJavaMethod originalArraycopy;
        private final Counters counters;

        public Templates(ArrayCopySnippets receiver, OptionValues options, Iterable<DebugHandlersFactory> factories, Factory factory, Providers providers,
                        SnippetReflectionProvider snippetReflection, TargetDescription target) {
            super(options, factories, providers, snippetReflection, target);
            this.counters = new Counters(factory);

            arraycopyGenericSnippet = snippet(receiver, "arraycopyGenericSnippet");
            arraycopyExactSnippet = snippet(receiver, "arraycopyExactSnippet");
            arraycopyExactStubCallSnippet = snippet(receiver, "arraycopyExactStubCallSnippet");
            arraycopyCheckcastSnippet = snippet(receiver, "arraycopyCheckcastSnippet");
            arraycopyNativeSnippet = snippet(null, "arraycopyNativeSnippet");
            checkcastArraycopyWithSlowPathWork = snippet(receiver, "checkcastArraycopyWithSlowPathWork");
            genericArraycopyWithSlowPathWork = snippet(receiver, "genericArraycopyWithSlowPathWork");
            exactArraycopyWithSlowPathWork = snippet(receiver, "exactArraycopyWithSlowPathWork");
        }

        protected SnippetInfo snippet(ArrayCopySnippets receiver, String methodName) {
            SnippetInfo info = snippet(ArrayCopySnippets.class, methodName, originalArraycopy(), receiver, LocationIdentity.any());
            return info;
        }

        public void lower(ArrayCopyNode arraycopy, LoweringTool tool) {
            lower(arraycopy, false, tool);
        }

        public void lower(ArrayCopyNode arraycopy, boolean mayExpandThisArraycopy, LoweringTool tool) {
            JavaKind elementKind = selectComponentKind(arraycopy);
            SnippetInfo snippetInfo;
            ArrayCopyTypeCheck arrayTypeCheck;

            ResolvedJavaType srcType = StampTool.typeOrNull(arraycopy.getSource().stamp(NodeView.DEFAULT));
            ResolvedJavaType destType = StampTool.typeOrNull(arraycopy.getDestination().stamp(NodeView.DEFAULT));
            if (!canBeArray(srcType) || !canBeArray(destType)) {
                // at least one of the objects is definitely not an array - use the native call
                // right away as the copying will fail anyways
                snippetInfo = arraycopyNativeSnippet;
                arrayTypeCheck = ArrayCopyTypeCheck.UNDEFINED_ARRAY_TYPE_CHECK;
            } else {
                ResolvedJavaType srcComponentType = srcType == null ? null : srcType.getComponentType();
                ResolvedJavaType destComponentType = destType == null ? null : destType.getComponentType();

                if (arraycopy.isExact()) {
                    // there is a sufficient type match - we don't need any additional type checks
                    snippetInfo = arraycopyExactStubCallSnippet;
                    arrayTypeCheck = ArrayCopyTypeCheck.NO_ARRAY_TYPE_CHECK;
                } else if (srcComponentType == null && destComponentType == null) {
                    // we don't know anything about the types - use the generic copying
                    snippetInfo = arraycopyGenericSnippet;
                    // no need for additional type check to avoid duplicated work
                    arrayTypeCheck = ArrayCopyTypeCheck.NO_ARRAY_TYPE_CHECK;
                } else if (GeneratePIC.getValue(options)) {
                    // use generic copying for AOT compilation
                    snippetInfo = arraycopyGenericSnippet;
                    arrayTypeCheck = ArrayCopyTypeCheck.NO_ARRAY_TYPE_CHECK;
                } else if (srcComponentType != null && destComponentType != null) {
                    if (!srcComponentType.isPrimitive() && !destComponentType.isPrimitive()) {
                        // it depends on the array content if the copy succeeds - we need
                        // a type check for every store
                        snippetInfo = arraycopyCheckcastSnippet;
                        arrayTypeCheck = ArrayCopyTypeCheck.NO_ARRAY_TYPE_CHECK;
                    } else {
                        // one object is an object array, the other one is a primitive array.
                        // this copy will always fail - use the native call right away
                        assert !srcComponentType.equals(destComponentType) : "must be handled by arraycopy.isExact()";
                        snippetInfo = arraycopyNativeSnippet;
                        arrayTypeCheck = ArrayCopyTypeCheck.UNDEFINED_ARRAY_TYPE_CHECK;
                    }
                } else {
                    ResolvedJavaType nonNullComponentType = srcComponentType != null ? srcComponentType : destComponentType;
                    if (nonNullComponentType.isPrimitive()) {
                        // one involved object is a primitive array - it is sufficient to directly
                        // compare the hub.
                        snippetInfo = arraycopyExactStubCallSnippet;
                        arrayTypeCheck = ArrayCopyTypeCheck.HUB_BASED_ARRAY_TYPE_CHECK;
                        elementKind = nonNullComponentType.getJavaKind();
                    } else {
                        // one involved object is an object array - the other array's element type
                        // may be primitive or object, hence we compare the layout helper.
                        snippetInfo = arraycopyCheckcastSnippet;
                        arrayTypeCheck = ArrayCopyTypeCheck.LAYOUT_HELPER_BASED_ARRAY_TYPE_CHECK;
                    }
                }
            }

            if (mayExpandThisArraycopy && snippetInfo == arraycopyExactStubCallSnippet) {
                snippetInfo = arraycopyExactSnippet;
            }

            // create the snippet
            Arguments args = new Arguments(snippetInfo, arraycopy.graph().getGuardsStage(), tool.getLoweringStage());
            args.add("src", arraycopy.getSource());
            args.add("srcPos", arraycopy.getSourcePosition());
            args.add("dest", arraycopy.getDestination());
            args.add("destPos", arraycopy.getDestinationPosition());
            args.add("length", arraycopy.getLength());
            if (snippetInfo != arraycopyNativeSnippet) {
                assert arrayTypeCheck != ArrayCopyTypeCheck.UNDEFINED_ARRAY_TYPE_CHECK;
                args.addConst("arrayTypeCheck", arrayTypeCheck);
            }
            Object locationIdentity = arraycopy.killsAnyLocation() ? LocationIdentity.any() : NamedLocationIdentity.getArrayLocation(elementKind);
            if (snippetInfo == arraycopyExactStubCallSnippet || snippetInfo == arraycopyExactSnippet) {
                assert elementKind != null;
                args.addConst("workSnippet", exactArraycopyWithSlowPathWork);
                args.addConst("elementKind", elementKind);
                args.addConst("locationIdentity", locationIdentity);
                args.addConst("elementKindCounter", counters.arraycopyCallCounters.get(elementKind));
                args.addConst("elementKindCopiedCounter", counters.arraycopyCallCopiedCounters.get(elementKind));
            }
            args.addConst("counters", counters);
            if (snippetInfo == arraycopyCheckcastSnippet) {
                args.addConst("workSnippet", checkcastArraycopyWithSlowPathWork);
                args.addConst("elementKind", JavaKind.Illegal);
            }
            if (snippetInfo == arraycopyGenericSnippet) {
                args.addConst("workSnippet", genericArraycopyWithSlowPathWork);
                args.addConst("elementKind", JavaKind.Illegal);
            }

            instantiate(args, arraycopy);
        }

        public void lower(ArrayCopyWithDelayedLoweringNode arraycopy, LoweringTool tool) {
            lower(arraycopy, false, tool);
        }

        public void lower(ArrayCopyWithDelayedLoweringNode arraycopy, boolean mayExpandArraycopyLoops, LoweringTool tool) {
            StructuredGraph graph = arraycopy.graph();

            if (arraycopy.getSnippet() == exactArraycopyWithSlowPathWork && mayExpandArraycopyLoops) {
                if (!graph.getGuardsStage().areDeoptsFixed()) {
                    // Don't lower until floating guards are fixed.
                    return;
                }
            } else {
                if (!graph.getGuardsStage().areFrameStatesAtDeopts()) {
                    // Don't lower until frame states are assigned to deoptimization points.
                    return;
                }
            }

            SnippetInfo snippetInfo = arraycopy.getSnippet();
            Arguments args = new Arguments(snippetInfo, graph.getGuardsStage(), tool.getLoweringStage());
            args.add("src", arraycopy.getSource());
            args.add("srcPos", arraycopy.getSourcePosition());
            args.add("dest", arraycopy.getDestination());
            args.add("destPos", arraycopy.getDestinationPosition());
            args.add("length", arraycopy.getLength());

            JavaKind elementKind = arraycopy.getElementKind();
            args.addConst("elementKind", (elementKind == null) ? JavaKind.Illegal : elementKind);

            Object locationIdentity = (elementKind == null) ? LocationIdentity.any() : NamedLocationIdentity.getArrayLocation(arraycopy.getElementKind());
            args.addConst("arrayLocation", locationIdentity);
            args.addConst("counters", counters);
            instantiate(args, arraycopy);
        }

        private static boolean canBeArray(ResolvedJavaType type) {
            return type == null || type.isJavaLangObject() || type.isArray();
        }

        public static JavaKind selectComponentKind(BasicArrayCopyNode arraycopy) {
            ResolvedJavaType srcType = StampTool.typeOrNull(arraycopy.getSource().stamp(NodeView.DEFAULT));
            ResolvedJavaType destType = StampTool.typeOrNull(arraycopy.getDestination().stamp(NodeView.DEFAULT));

            if (srcType == null || !srcType.isArray() || destType == null || !destType.isArray()) {
                return null;
            }
            if (!destType.getComponentType().isAssignableFrom(srcType.getComponentType())) {
                return null;
            }
            if (!arraycopy.isExact()) {
                return null;
            }
            return srcType.getComponentType().getJavaKind();
        }

        /**
         * Instantiate the snippet template and fix up the FrameState of any Invokes of
         * System.arraycopy and propagate the captured bci in the ArrayCopySlowPathNode.
         *
         * @param args
         * @param arraycopy
         */
        private void instantiate(Arguments args, BasicArrayCopyNode arraycopy) {
            StructuredGraph graph = arraycopy.graph();
            SnippetTemplate template = template(arraycopy, args);
            UnmodifiableEconomicMap<Node, Node> replacements = template.instantiate(providers.getMetaAccess(), arraycopy, SnippetTemplate.DEFAULT_REPLACER, args, false);
            for (Node originalNode : replacements.getKeys()) {
                if (originalNode instanceof InvokeNode) {
                    InvokeNode invoke = (InvokeNode) replacements.get(originalNode);
                    assert invoke.asNode().graph() == graph;
                    CallTargetNode call = invoke.callTarget();

                    if (!call.targetMethod().equals(originalArraycopy)) {
                        throw new GraalError("unexpected invoke %s in snippet", call.targetMethod());
                    }
                    // Here we need to fix the bci of the invoke
                    invoke.setBci(arraycopy.getBci());
                    invoke.setStateDuring(null);
                    invoke.setStateAfter(null);
                    if (arraycopy.stateDuring() != null) {
                        invoke.setStateDuring(arraycopy.stateDuring());
                    } else {
                        assert arraycopy.stateAfter() != null : arraycopy;
                        invoke.setStateAfter(arraycopy.stateAfter());
                    }
                } else if (originalNode instanceof InvokeWithExceptionNode) {
                    throw new GraalError("unexpected invoke with exception %s in snippet", originalNode);
                } else if (originalNode instanceof ArrayCopyWithDelayedLoweringNode) {
                    ArrayCopyWithDelayedLoweringNode slowPath = (ArrayCopyWithDelayedLoweringNode) replacements.get(originalNode);
                    assert arraycopy.stateAfter() != null : arraycopy;
                    assert slowPath.stateAfter() == arraycopy.stateAfter() : "States do not match for slowpath=" + slowPath + " and array copy=" + arraycopy + " slowPathState=" +
                                    slowPath.stateAfter() + " and arraycopyState=" + arraycopy.stateAfter();
                    slowPath.setBci(arraycopy.getBci());
                }
            }
            GraphUtil.killCFG(arraycopy);
        }

        private ResolvedJavaMethod originalArraycopy() throws GraalError {
            if (originalArraycopy == null) {
                try {
                    originalArraycopy = findMethod(providers.getMetaAccess(), System.class, "arraycopy");
                } catch (SecurityException e) {
                    throw new GraalError(e);
                }
            }
            return originalArraycopy;
        }
    }
}

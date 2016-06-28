/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package com.oracle.graal.phases.common.instrumentation;

import java.util.HashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.oracle.graal.graph.Node;
import com.oracle.graal.graph.NodeFlood;
import com.oracle.graal.nodes.AbstractEndNode;
import com.oracle.graal.nodes.FixedNode;
import com.oracle.graal.nodes.FixedWithNextNode;
import com.oracle.graal.nodes.LoopEndNode;
import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.debug.instrumentation.InstrumentationNode;
import com.oracle.graal.nodes.debug.instrumentation.MonitorProxyNode;
import com.oracle.graal.nodes.java.MonitorIdNode;
import com.oracle.graal.nodes.virtual.AllocatedObjectNode;
import com.oracle.graal.nodes.virtual.CommitAllocationNode;
import com.oracle.graal.nodes.virtual.VirtualObjectNode;
import com.oracle.graal.phases.Phase;

/**
 * The {@code HighTierReconcileInstrumentationPhase} reconciles the InstrumentationNodes according
 * to the optimizations at the high tier, e.g., the partial escape analysis. It clones the
 * InstrumentationNode and inserts at the CommitAllocationNode that includes the allocation/lock
 * targeted by the InstrumentationNode.
 */
public class HighTierReconcileInstrumentationPhase extends Phase {

    @Override
    protected void run(StructuredGraph graph) {
        // iterate through all CommitAllocationNodes, and clone the InstrumentationNodes targeting
        // allocation/lock held by this CommitAllocationNode
        for (CommitAllocationNode commit : graph.getNodes().filter(CommitAllocationNode.class)) {
            InstrumentationAggregation aggr = new InstrumentationAggregation(graph, commit);
            // iterate through all VirtualObjectNodes held by the CommitAllocationNode, clone if any
            // InstrumentationNode targets one of these VirtualObjectNodes
            for (int objIndex = 0; objIndex < commit.getVirtualObjects().size(); objIndex++) {
                VirtualObjectNode virtual = commit.getVirtualObjects().get(objIndex);
                aggr.cloneInstrumentationIf(in -> in.getTarget() == virtual,
                                () -> getAllocatedObject(graph, commit, virtual));
            }
            // iterate through all MonitorIdNodes held by the CommitAllocationNode, clone if any
            // InstrumentationNode targets one of these MonitorIdNodes (via MonitorProxyNode)
            for (int objIndex = 0; objIndex < commit.getVirtualObjects().size(); objIndex++) {
                VirtualObjectNode virtual = commit.getVirtualObjects().get(objIndex);
                for (MonitorIdNode monitorId : commit.getLocks(objIndex)) {
                    aggr.cloneInstrumentationIf(in -> in.getTarget() instanceof MonitorProxyNode && ((MonitorProxyNode) in.getTarget()).getMonitorId() == monitorId,
                                    () -> new MonitorProxyNode(getAllocatedObject(graph, commit, virtual), monitorId));
                }
            }
        }
        // remove InstrumentationNodes that still target virtual nodes
        for (InstrumentationNode instrumentationNode : graph.getNodes().filter(InstrumentationNode.class)) {
            ValueNode target = instrumentationNode.getTarget();
            if (target instanceof VirtualObjectNode) {
                graph.removeFixed(instrumentationNode);
            } else if (target instanceof MonitorProxyNode) {
                MonitorProxyNode proxy = (MonitorProxyNode) target;
                if (proxy.object() == null) {
                    graph.removeFixed(instrumentationNode);
                }
            }
        }
    }

    /**
     * The {@code InstrumentationAggregation} maintains an inserting location after
     * CommitAllocationNode such that the cloned InstrumentationNodes would appear in the order of
     * the allocations.
     */
    class InstrumentationAggregation {

        private StructuredGraph graph;
        private CommitAllocationNode commit;
        private FixedWithNextNode insertAfter;

        InstrumentationAggregation(StructuredGraph graph, CommitAllocationNode commit) {
            this.graph = graph;
            this.commit = commit;
            this.insertAfter = commit;
        }

        void cloneInstrumentationIf(Predicate<InstrumentationNode> filter, Supplier<ValueNode> newTargetSupplier) {
            for (InstrumentationNode instrumentationNode : graph.getNodes().filter(InstrumentationNode.class)) {
                if (!filter.test(instrumentationNode)) {
                    continue;
                }
                if (!isCFGAccessible(instrumentationNode, commit)) {
                    // clone instrumentation node only if the CommitAllocationNode is accessible
                    // from the original instrumentation node
                    continue;
                }
                InstrumentationNode clone = (InstrumentationNode) instrumentationNode.copyWithInputs();
                // update the clone instrumentation node with the new target
                ValueNode newTarget = newTargetSupplier.get();
                if (!newTarget.isAlive()) {
                    graph.addWithoutUnique(newTarget);
                }
                clone.replaceFirstInput(clone.getTarget(), newTarget);
                // update weak dependencies of the clone instrumentation node where the dependency
                // is also a VirtualObjectNode. This is common when one allocation in the
                // CommitAllocationNode depends on another allocation.
                for (ValueNode input : clone.getWeakDependencies()) {
                    if ((input instanceof VirtualObjectNode) && (commit.getVirtualObjects().contains(input))) {
                        clone.replaceFirstInput(input, getAllocatedObject(graph, commit, (VirtualObjectNode) input));
                    }
                }

                graph.addAfterFixed(insertAfter, clone);
                insertAfter = clone;
            }
        }

    }

    private final HashMap<FixedWithNextNode, NodeFlood> cachedNodeFloods = new HashMap<>();

    /**
     * @return true if there is a control flow path between {@code from} and {@code to}.
     */
    private boolean isCFGAccessible(FixedWithNextNode from, FixedNode to) {
        NodeFlood flood = cachedNodeFloods.get(from);
        if (flood == null) {
            flood = from.graph().createNodeFlood();
            flood.add(from);
            for (Node current : flood) {
                if (current instanceof LoopEndNode) {
                    continue;
                } else if (current instanceof AbstractEndNode) {
                    flood.add(((AbstractEndNode) current).merge());
                } else {
                    flood.addAll(current.successors());
                }
            }
            cachedNodeFloods.put(from, flood);
        }
        return flood.isMarked(to);
    }

    /**
     * Get/generate the AllocatedObjectNode for the given VirtualObjectNode in the given
     * CommitAllocationNode.
     */
    private static AllocatedObjectNode getAllocatedObject(StructuredGraph graph, CommitAllocationNode commit, VirtualObjectNode virtual) {
        // search if the AllocatedObjectNode already exists
        for (AllocatedObjectNode allocatedObject : graph.getNodes().filter(AllocatedObjectNode.class)) {
            if (allocatedObject.getCommit() == commit && allocatedObject.getVirtualObject() == virtual) {
                return allocatedObject;
            }
        }
        // create one if the AllocatedObjectNode does not exist
        AllocatedObjectNode allocatedObject = graph.addWithoutUnique(new AllocatedObjectNode(virtual));
        allocatedObject.setCommit(commit);
        return allocatedObject;
    }

}

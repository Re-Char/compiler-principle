import org.bytedeco.llvm.LLVM.*;
import static org.bytedeco.llvm.global.LLVM.*;
import org.llvm4j.llvm4j.Module;

import java.util.*;

public class EliminateDead {
    private LLVMModuleRef modRef;
    private boolean changed;
    private int eliminatedCount = 0;

    private Map<LLVMBasicBlockRef, List<LLVMBasicBlockRef>> predecessors = new HashMap<>();
    private Map<LLVMBasicBlockRef, List<LLVMBasicBlockRef>> successors = new HashMap<>();

    public EliminateDead(Module module) {
        this.modRef = module.getRef();
    }

    public int optimize() {
        do {
            changed = false;
            eliminateInstructionsAfterReturn();
            buildCFG();
            eliminateUnreachableBlocks();
            simplifyConstantBranches();
            if (changed) buildCFG();
            eliminateRedundantJumps();
        } while (changed);
        return eliminatedCount;
    }

    private void buildCFG() {
        predecessors.clear();
        successors.clear();

        for (LLVMValueRef func = LLVMGetFirstFunction(modRef); func != null; func = LLVMGetNextFunction(func)) {
            if (LLVMCountBasicBlocks(func) == 0)
                continue; // 跳过声明

            for (LLVMBasicBlockRef block = LLVMGetFirstBasicBlock(func); block != null; block = LLVMGetNextBasicBlock(block)) {
                predecessors.put(block, new ArrayList<>());
                successors.put(block, new ArrayList<>());
            }

            for (LLVMBasicBlockRef block = LLVMGetFirstBasicBlock(func); block != null; block = LLVMGetNextBasicBlock(block)) {
                LLVMValueRef termInst = LLVMGetBasicBlockTerminator(block);
                if (termInst == null)
                    continue;

                int numSuccessors = LLVMGetNumSuccessors(termInst);
                for (int i = 0; i < numSuccessors; i++) {
                    LLVMBasicBlockRef successor = LLVMGetSuccessor(termInst, i);
                    successors.get(block).add(successor);
                    predecessors.get(successor).add(block);
                }
            }
        }
    }

    private void eliminateUnreachableBlocks() {
        for (LLVMValueRef func = LLVMGetFirstFunction(modRef); func != null; func = LLVMGetNextFunction(func)) {
            if (LLVMCountBasicBlocks(func) == 0)
                continue;

            LLVMBasicBlockRef entry = LLVMGetEntryBasicBlock(func);
            Set<LLVMBasicBlockRef> reachable = new HashSet<>();
            Queue<LLVMBasicBlockRef> queue = new LinkedList<>();

            queue.add(entry);
            reachable.add(entry);

            while (!queue.isEmpty()) {
                LLVMBasicBlockRef current = queue.poll();

                for (LLVMBasicBlockRef succ : successors.getOrDefault(current, Collections.emptyList())) {
                    if (!reachable.contains(succ)) {
                        reachable.add(succ);
                        queue.add(succ);
                    }
                }
            }

            List<LLVMBasicBlockRef> toRemove = new ArrayList<>();
            for (LLVMBasicBlockRef block = LLVMGetFirstBasicBlock(func); block != null; block = LLVMGetNextBasicBlock(block)) {
                if (!reachable.contains(block)) {
                    toRemove.add(block);
                }
            }

            for (LLVMBasicBlockRef block : toRemove) {
                // System.out.println("删除不可达基本块: " + LLVMPrintValueToString(LLVMBasicBlockAsValue(block)).getString());
                LLVMRemoveBasicBlockFromParent(block);
                changed = true;
                eliminatedCount++;
            }
        }
    }

    private void simplifyConstantBranches() {
        for (LLVMValueRef func = LLVMGetFirstFunction(modRef); func != null; func = LLVMGetNextFunction(func)) {
            if (LLVMCountBasicBlocks(func) == 0)
                continue;

            for (LLVMBasicBlockRef block = LLVMGetFirstBasicBlock(func); block != null; block = LLVMGetNextBasicBlock(
                    block)) {
                LLVMValueRef termInst = LLVMGetBasicBlockTerminator(block);
                if (termInst == null)
                    continue;

                if (LLVMGetInstructionOpcode(termInst) == LLVMBr && LLVMGetNumOperands(termInst) > 1) {
                    LLVMValueRef condition = LLVMGetOperand(termInst, 0);
                    if (LLVMIsAConstantInt(condition) != null) {
                        long condValue = LLVMConstIntGetSExtValue(condition);
                        boolean condBool = condValue != 0;

                        LLVMBasicBlockRef trueBlock = LLVMValueAsBasicBlock(LLVMGetOperand(termInst, 2));
                        LLVMBasicBlockRef falseBlock = LLVMValueAsBasicBlock(LLVMGetOperand(termInst, 1));

                        LLVMBasicBlockRef targetBlock = condBool ? trueBlock : falseBlock;

                        // System.out.println("基于常量条件 " + condBool + " 替换条件分支为无条件跳转到 " +
                        // LLVMGetValueName(LLVMBasicBlockAsValue(targetBlock)).getString());

                        // 创建新的无条件跳转
                        LLVMBuilderRef builder = LLVMCreateBuilder();
                        LLVMPositionBuilderBefore(builder, termInst);
                        LLVMBuildBr(builder, targetBlock);
                        LLVMDisposeBuilder(builder);

                        LLVMInstructionEraseFromParent(termInst);

                        changed = true;
                    }
                }
            }
        }
    }

    private void eliminateRedundantJumps() {
        for (LLVMValueRef func = LLVMGetFirstFunction(modRef); func != null; func = LLVMGetNextFunction(func)) {
            if (LLVMCountBasicBlocks(func) == 0)
                continue;

            Map<LLVMBasicBlockRef, LLVMBasicBlockRef> blocksToMerge = new HashMap<>();

            for (LLVMBasicBlockRef block = LLVMGetFirstBasicBlock(func); block != null; block = LLVMGetNextBasicBlock(block)) {
                LLVMValueRef termInst = LLVMGetBasicBlockTerminator(block);
                if (termInst == null)
                    continue;

                if (LLVMGetInstructionOpcode(termInst) == LLVMBr && LLVMGetNumOperands(termInst) == 1) {
                    LLVMBasicBlockRef target = LLVMValueAsBasicBlock(LLVMGetOperand(termInst, 0));

                    if (predecessors.get(target).size() == 1 && !target.equals(block)) {
                        // System.out.println("找到冗余跳转: " +
                        //         LLVMGetValueName(LLVMBasicBlockAsValue(block)).getString() + " -> " +
                        //         LLVMGetValueName(LLVMBasicBlockAsValue(target)).getString());
                        blocksToMerge.put(block, target);
                    }
                }
            }

            int size = blocksToMerge.size();
            while (size > 0) {
                // System.out.println("找到 " + size + " 个冗余跳转块对");
                Map.Entry<LLVMBasicBlockRef, LLVMBasicBlockRef> entry = blocksToMerge.entrySet().iterator().next();
                LLVMBasicBlockRef source = entry.getKey();
                LLVMBasicBlockRef target = entry.getValue();

                // System.out.println("合并基本块: " + LLVMGetValueName(LLVMBasicBlockAsValue(source)).getString() +
                //         " 和 " + LLVMGetValueName(LLVMBasicBlockAsValue(target)).getString());
                LLVMValueRef termInst = LLVMGetBasicBlockTerminator(source);
                LLVMInstructionEraseFromParent(termInst);
                LLVMBuilderRef builder = LLVMCreateBuilder();
                LLVMPositionBuilderAtEnd(builder, source);
                while (LLVMGetFirstInstruction(target) != null) {
                    LLVMValueRef inst = LLVMGetFirstInstruction(target);
                    LLVMInstructionRemoveFromParent(inst);
                    LLVMInsertIntoBuilderWithName(builder, inst, LLVMGetValueName(inst));
                }
                LLVMDisposeBuilder(builder);
                if (blocksToMerge.containsKey(target)) {
                    LLVMBasicBlockRef blockToReplace = blocksToMerge.get(target);
                    // blocksToMerge.remove(source);
                    blocksToMerge.remove(target);
                    blocksToMerge.put(source, blockToReplace);
                } else {
                    blocksToMerge.remove(source);
                }
                LLVMRemoveBasicBlockFromParent(target);
                size--;
                eliminatedCount++;
                // System.out.println("删除冗余基本块: " + LLVMGetValueName(LLVMBasicBlockAsValue(target)).getString());

                changed = true;
            }
        }
    }

    private void eliminateInstructionsAfterReturn() {
        boolean localChanged = false;
        int removedCount = 0;

        for (LLVMValueRef func = LLVMGetFirstFunction(modRef); func != null; func = LLVMGetNextFunction(func)) {
            if (LLVMCountBasicBlocks(func) == 0)
                continue;

            for (LLVMBasicBlockRef block = LLVMGetFirstBasicBlock(func); block != null; block = LLVMGetNextBasicBlock(block)) {
                List<LLVMValueRef> instructionsToRemove = new ArrayList<>();
                boolean foundTerminator = false;
                for (LLVMValueRef inst = LLVMGetFirstInstruction(block); inst != null; inst = LLVMGetNextInstruction(
                        inst)) {
                    int opcode = LLVMGetInstructionOpcode(inst);
                    if (foundTerminator) {
                        instructionsToRemove.add(inst);
                    } else if (opcode == LLVMRet || opcode == LLVMBr) {
                        foundTerminator = true;
                    }
                }

                for (int i = instructionsToRemove.size() - 1; i >= 0; i--) {
                    LLVMValueRef inst = instructionsToRemove.get(i);
                    // System.out.println("删除终结指令后的无效指令: " + LLVMPrintValueToString(inst).getString());
                    LLVMInstructionEraseFromParent(inst);
                    removedCount++;
                    localChanged = true;
                }
            }
        }

        if (localChanged) {
            changed = true;
            eliminatedCount += removedCount;
            // System.out.println("删除了 " + removedCount + " 条终结指令后的无效指令");
        }
    }
}
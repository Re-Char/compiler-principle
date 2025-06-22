import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.bytedeco.llvm.LLVM.LLVMValueRef;
import org.bytedeco.llvm.global.LLVM;
import org.llvm4j.llvm4j.Module;

public class GenerateASM {
    private Module module;
    private StringBuilder content;
    private ASMBuilder asmBuilder;
    private Map<String, String> varMap;
    private Map<String, int[]> varLife;
    private RegisterAllocate registerAllocate;
    private Map<String, LLVMValueRef> condReplaceInst;
    public GenerateASM(Module module) {
        this.module = module;
        this.content = new StringBuilder();
        this.asmBuilder = new ASMBuilder(content);
        this.varMap = new HashMap<>();
        this.varLife = new HashMap<>();
        this.condReplaceInst = new HashMap<>();
    }

    private class Register {
        private String name;

        public Register(String name) {
            this.name = name;
        }

        public String getName() {
            return this.name;
        }

    }

    private class RegisterAllocate {
        private class LiveInterval implements Comparable<LiveInterval> {
            private String name;
            private int start;
            private int end;
            private String location;

            public LiveInterval(String name, int start, int end) {
                this.name = name;
                this.start = start;
                this.end = end;
                this.location = null;
            }

            public String getName() {
                return this.name;
            }

            public int getStart() {
                return this.start;
            }

            public int getEnd() {
                return this.end;
            }

            public String getLocation() {
                return this.location;
            }

            public void setLocation(String location) {
                this.location = location;
            }

            @Override
            public int compareTo(LiveInterval other) {
                return Integer.compare(this.start, other.start);
            }
        }

        private int stackSize;
        private int nextOffset;
        private ArrayList<Register> availableRegisters; 
        private ArrayList<LiveInterval> activeIntervals; 
        private ArrayList<LiveInterval> intervals; 
        private Map<String, Register> allRegistersMap; 
        public RegisterAllocate() {
            stackSize = 0;
            nextOffset = 0;
            allRegistersMap = new HashMap<>(); 
            availableRegisters = new ArrayList<>();
            for (int i = 0; i <= 11; i++) {
                Register reg = new Register("s" + String.valueOf(i));
                availableRegisters.add(reg);
                allRegistersMap.put(reg.getName(), reg);
            }
            for (int i = 0; i <= 7; i++) {
                Register reg = new Register("a" + String.valueOf(i));
                availableRegisters.add(reg);
                allRegistersMap.put(reg.getName(), reg);
            }
            for (int i = 2; i <= 6; i++) {
                Register reg = new Register("t" + String.valueOf(i));
                availableRegisters.add(reg);
                allRegistersMap.put(reg.getName(), reg);
            }
            intervals = new ArrayList<>();
            activeIntervals = new ArrayList<>(); 
            for (Map.Entry<String, int[]> entry : varLife.entrySet()) {
                String name = entry.getKey();
                int[] lifespan = entry.getValue();
                intervals.add(new LiveInterval(name, lifespan[0], lifespan[1]));
            }
            Collections.sort(intervals);
        }

        public void linearScanRegisterAllocation() {
            varMap.clear();
            for (LiveInterval currentInterval : intervals) {
                expireOldIntervals(currentInterval); 
                if (availableRegisters.isEmpty()) { 
                    spillAtInterval(currentInterval);
                } else {
                    Register regToUse = availableRegisters.remove(0); 
                    currentInterval.setLocation(regToUse.getName());
                    varMap.put(currentInterval.getName(), currentInterval.getLocation());
                    activeIntervals.add(currentInterval);
                    activeIntervals.sort(Comparator.comparingInt(LiveInterval::getEnd));
                }
            }
        }

        private void expireOldIntervals(LiveInterval currentInterval) {
            Iterator<LiveInterval> iterator = activeIntervals.iterator();
            while (iterator.hasNext()) {
                LiveInterval activeInt = iterator.next();
                if (activeInt.getEnd() >= currentInterval.getStart()) break; 
                iterator.remove(); 
                String freedRegName = activeInt.getLocation();
                if (freedRegName != null && !freedRegName.contains("(sp)")) {
                    Register freedReg = allRegistersMap.get(freedRegName); 
                    if (freedReg != null) {
                        availableRegisters.add(freedReg); 
                    } else {
                        System.err.println("Error: Freed register " + freedRegName + " not found in allRegistersMap.");
                    }
                }
            }
        }

        private void spillAtInterval(LiveInterval currentInterval) {
            LiveInterval spillTarget = activeIntervals.get(activeIntervals.size() - 1);
            if (spillTarget.getEnd() > currentInterval.getEnd()) {
                String regToReassign = spillTarget.getLocation(); 
                currentInterval.setLocation(regToReassign); 
                varMap.put(currentInterval.getName(), currentInterval.getLocation());
                spillTarget.setLocation(String.valueOf(nextOffset) + "(sp)");
                varMap.put(spillTarget.getName(), spillTarget.getLocation());
                nextOffset += 4;
                activeIntervals.remove(spillTarget);
                activeIntervals.add(currentInterval);
                activeIntervals.sort(Comparator.comparingInt(LiveInterval::getEnd));
            } else {
                currentInterval.setLocation(String.valueOf(nextOffset) + "(sp)");
                varMap.put(currentInterval.getName(), currentInterval.getLocation());
                nextOffset += 4;
            }
        }

        public void generateStackSize(int maxInstructionPoint) {
            linearScanRegisterAllocation();
            int count = 4;
            this.stackSize = (nextOffset + 15) & ~15;
            for (LiveInterval i : intervals) {
                if (i.getLocation() != null && i.getLocation().contains("sp")) {
                    String name = i.getName();
                    i.setLocation(String.valueOf(stackSize - count) + "(sp)");
                    count += 4;
                    varMap.put(name, i.getLocation());
                }
            }
        }

        public int getStackSize() {
            return stackSize;
        }
    }

    public void scan() {
        var modRef = module.getRef();
        for (var globalVar = LLVM.LLVMGetFirstGlobal(modRef); globalVar != null; globalVar = LLVM
                .LLVMGetNextGlobal(globalVar)) {
            String varName = LLVM.LLVMGetValueName(globalVar).getString();
            String op = getOperandAsString(LLVM.LLVMGetInitializer(globalVar));
            asmBuilder.buildGlobalVar(varName, op);
        }
        var function = LLVM.LLVMGetFirstFunction(modRef);
        if (function != null) {
            int spSize = getVariableCount(function);
            String functionName = LLVM.LLVMGetValueName(function).getString();
            asmBuilder.buildFuncdef(functionName, String.valueOf(spSize));
            for (var basicBlock = LLVM.LLVMGetFirstBasicBlock(function); basicBlock != null; basicBlock = LLVM
                    .LLVMGetNextBasicBlock(basicBlock)) {
                String currentBlockLabel = LLVM.LLVMGetValueName(LLVM.LLVMBasicBlockAsValue(basicBlock)).getString();
                asmBuilder.buildLabel(currentBlockLabel);
                for (var instruction = LLVM.LLVMGetFirstInstruction(basicBlock); instruction != null; instruction = LLVM.LLVMGetNextInstruction(instruction)) {
                    int opcode = LLVM.LLVMGetInstructionOpcode(instruction);
                    String instName = LLVM.LLVMGetValueName(instruction).getString();
                    if (opcode == LLVM.LLVMRet) {
                        var operand0 = LLVM.LLVMGetOperand(instruction, 0);
                        if (LLVM.LLVMIsAGlobalVariable(operand0) != null) {
                            asmBuilder.buildLoadAndStore("la", "a0", getOperandAsString(operand0));
                            asmBuilder.buildLoadAndStore("lw", "a0", "0(a0)");
                        } else if (LLVM.LLVMIsAConstant(operand0) != null)
                            asmBuilder.buildLoadAndStore("li", "a0", getOperandAsString(operand0));
                        else
                            asmBuilder.buildLoadAndStore("mv", "a0", varMap.get(getOperandAsString(operand0)));
                        asmBuilder.buildRet(String.valueOf(spSize));
                    } else if (opcode == LLVM.LLVMLoad) {
                        String registerName = varMap.get(instName);
                        var operand0 = LLVM.LLVMGetOperand(instruction, 0);
                        handleLoad(registerName, operand0);
                    } else if (opcode == LLVM.LLVMStore) {
                        var operand0 = LLVM.LLVMGetOperand(instruction, 0);
                        String op0RegName = varMap.get(getOperandAsString(operand0));
                        String reg = "t0";
                        if (op0RegName != null && !op0RegName.contains("sp")) reg = op0RegName;
                        else handleLoad("t0", operand0);
                        var operand1 = LLVM.LLVMGetOperand(instruction, 1);
                        if (LLVM.LLVMIsAGlobalVariable(operand1) != null) {
                            asmBuilder.buildLoadAndStore("la", "t1", getOperandAsString(operand1));
                            asmBuilder.buildLoadAndStore("sw", reg, "0(t1)");
                        } else {
                            String op1RegName = varMap.get(getOperandAsString(operand1));
                            if (op1RegName.contains("sp"))
                                asmBuilder.buildLoadAndStore("sw", reg, op1RegName);
                            else 
                                asmBuilder.buildLoadAndStore("mv", op1RegName, reg);
                        }
                    } else if (opcode >= LLVM.LLVMAdd && opcode <= LLVM.LLVMSRem) {
                        String registerName = varMap.get(instName);
                        var operand0 = LLVM.LLVMGetOperand(instruction, 0);
                        var operand1 = LLVM.LLVMGetOperand(instruction, 1);
                        String op0RegName = varMap.get(getOperandAsString(operand0));
                        String op1RegName = varMap.get(getOperandAsString(operand1));
                        String dest = "t0";
                        String src1 = "t0";
                        String src2 = "t1";
                        if (op0RegName != null && !op0RegName.contains("sp"))
                            src1 = op0RegName;
                        else
                            handleLoad("t0", operand0);
                        if (op1RegName != null && !op1RegName.contains("sp"))
                            src2 = op1RegName;
                        else
                            handleLoad("t1", operand1);
                        if (!registerName.contains("sp"))
                            dest = registerName;
                        switch (opcode) {
                            case 8:
                                asmBuilder.buildCal("add", dest, src1, src2);
                                break;
                            case 10:
                                asmBuilder.buildCal("sub", dest, src1, src2);
                                break;
                            case 12:
                                asmBuilder.buildCal("mul", dest, src1, src2);
                                break;
                            case 15:
                                asmBuilder.buildCal("div", dest, src1, src2);
                                break;
                            case 18:
                                asmBuilder.buildCal("rem", dest, src1, src2);
                                break;
                        }
                        if (registerName.contains("sp")) 
                            asmBuilder.buildLoadAndStore("sw", "t0", registerName);
                    } else if (opcode == LLVM.LLVMBr) {
                        int opcodeNum = LLVM.LLVMGetNumOperands(instruction);
                        if (opcodeNum == 1) {
                            String label = LLVM.LLVMGetValueName(LLVM.LLVMGetOperand(instruction, 0)).getString();
                            asmBuilder.buildJump(label);
                        } else if (opcodeNum > 1) {
                            var operand = LLVM.LLVMGetOperand(instruction, 0);
                            var inst = condReplaceInst.get(getOperandAsString(operand));
                            String label1 = LLVM.LLVMGetValueName(LLVM.LLVMGetOperand(instruction, 2)).getString();
                            String label2 = LLVM.LLVMGetValueName(LLVM.LLVMGetOperand(instruction, 1)).getString();
                            var operand0 = LLVM.LLVMGetOperand(inst, 0);
                            var operand1 = LLVM.LLVMGetOperand(inst, 1);
                            String op1 = "t0";
                            String op0RegName = varMap.get(getOperandAsString(operand0));
                            if (op0RegName != null && !op0RegName.contains("sp")) op1 = op0RegName;
                            else handleLoad("t0", operand0);
                            String op2 = "t1";
                            String op1RegName = varMap.get(getOperandAsString(operand1));
                            if (op1RegName != null && !op1RegName.contains("sp")) op2 = op1RegName;
                            else
                                handleLoad("t1", operand1);
                            int predicate = LLVM.LLVMGetICmpPredicate(inst);
                            switch (predicate) {
                                case LLVM.LLVMIntEQ:
                                    asmBuilder.buildBr("beq", op1, op2, label1);
                                    break;
                                case LLVM.LLVMIntNE:
                                    asmBuilder.buildBr("bne", op1, op2, label1);
                                    break;
                                case LLVM.LLVMIntSGT:
                                    asmBuilder.buildBr("bgt", op1, op2, label1);
                                    break;
                                case LLVM.LLVMIntSGE:
                                    asmBuilder.buildBr("bge", op1, op2, label1);
                                    break;
                                case LLVM.LLVMIntSLT:
                                    asmBuilder.buildBr("blt", op1, op2, label1);
                                    break;
                                case LLVM.LLVMIntSLE:
                                    asmBuilder.buildBr("ble", op1, op2, label1);
                                    break;
                            }
                            asmBuilder.buildJump(label2);
                        }
                    }
                }
            }
        }
    }

    private String getOperandAsString(LLVMValueRef operand) {
        if (operand == null)
            return "null";
        if (LLVM.LLVMIsAGlobalVariable(operand) == null && LLVM.LLVMIsAConstant(operand) != null)
            return LLVM.LLVMConstIntGetSExtValue(operand) + "";
        String name = LLVM.LLVMGetValueName(operand).getString();
        if (name != null && !name.isEmpty())
            return name;
        return LLVM.LLVMTypeOf(operand).toString();
    }

    private int getVariableCount(LLVMValueRef function) {
        int count = 1;
        for (var basicBlock = LLVM.LLVMGetFirstBasicBlock(function); basicBlock != null; basicBlock = LLVM
                .LLVMGetNextBasicBlock(basicBlock)) {
            for (var instruction = LLVM.LLVMGetFirstInstruction(basicBlock); instruction != null; instruction = LLVM
                    .LLVMGetNextInstruction(instruction)) {
                String name = LLVM.LLVMGetValueName(instruction).getString();
                if (name.contains("extract") || name.contains("gttmp") || name.contains("lttmp")
                    || name.contains("eqtmp") || name.contains("neqtmp") || name.contains("letmp") || name.contains("getmp"))
                    continue;
                if (name.contains("cond")) {
                    var prevInstruction = LLVM.LLVMGetPreviousInstruction(instruction);
                    var prevInstruction2 = LLVM.LLVMGetPreviousInstruction(prevInstruction);
                    condReplaceInst.put(name, prevInstruction2);
                    continue;
                }
                if (LLVM.LLVMIsABranchInst(instruction) != null) {
                    var operand = LLVM.LLVMGetOperand(instruction, 0);
                    if (LLVM.LLVMGetNumOperands(instruction) > 1) {
                        String opName = LLVM.LLVMGetValueName(operand).getString();
                        var inst = condReplaceInst.get(opName);
                        String operand0 = LLVM.LLVMGetValueName(LLVM.LLVMGetOperand(inst, 0)).getString();
                        String operand1 = LLVM.LLVMGetValueName(LLVM.LLVMGetOperand(inst, 1)).getString();
                        varLife.get(operand0)[1] += 2;
                        if (!operand1.equals("")) varLife.get(operand1)[1] += 1;
                        continue;
                    }
                }
                if (!name.equals(""))
                    varLife.put(name, new int[] { count, count });
                for (int index = 0; index < LLVM.LLVMGetNumOperands(instruction); index++) {
                    var operand = LLVM.LLVMGetOperand(instruction, index);
                    if (LLVM.LLVMIsAConstant(operand) == null && LLVM.LLVMIsAGlobalVariable(operand) == null) {
                        String operandName = LLVM.LLVMGetValueName(operand).getString();
                        if (varLife.containsKey(operandName))
                            varLife.get(operandName)[1] = count;
                    }
                }
                count++;
            }
        }
        registerAllocate = new RegisterAllocate();
        registerAllocate.generateStackSize(count);
        int stackSize = registerAllocate.getStackSize();
        return stackSize;
    }

    private void handleLoad(String regName, LLVMValueRef operand) {
        if (LLVM.LLVMIsAGlobalVariable(operand) != null) {
            asmBuilder.buildLoadAndStore("la", "t1", getOperandAsString(operand));
            asmBuilder.buildLoadAndStore("lw", regName, "0(t1)");
        } else if (LLVM.LLVMIsAConstant(operand) != null) {
            asmBuilder.buildLoadAndStore("li", regName, getOperandAsString(operand));
        } else {
            String opRegName = varMap.get(getOperandAsString(operand));
            if (opRegName.contains("sp"))
                asmBuilder.buildLoadAndStore("lw", regName, opRegName);
            else {
                if (regName.contains("sp"))
                    asmBuilder.buildLoadAndStore("sw", opRegName, regName);
                else
                    asmBuilder.buildLoadAndStore("mv", regName, opRegName);
            }
        }
    }

    public void generateOutputFile(File outputFile) {
        scan();
        try (FileWriter writer = new FileWriter(outputFile)) {
            writer.write(this.content.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

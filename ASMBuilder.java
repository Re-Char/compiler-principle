public class ASMBuilder {
    private StringBuilder content;

    public ASMBuilder(StringBuilder content) {
        this.content = content;
    }

    public void buildGlobalVar(String varName, String op) {
        content.append("  .data\n");
        content.append(varName).append(":\n");
        content.append("  .word ").append(op).append("\n\n");
    }

    public void buildFuncdef(String funcDef, String spSize) {
        content.append("  .text\n");
        content.append("  .globl ").append(funcDef).append("\n");
        content.append(funcDef).append(":\n");
        if (Integer.parseInt(spSize) > 0) content.append("  addi sp, sp, -").append(spSize).append("\n");
        else content.append("  addi sp, sp, ").append(spSize).append("\n");
        // content.append(funcDef).append("Entry:\n");
    }

    public void buildLoadAndStore(String op, String reg, String value) {
        content.append("  ").append(op).append(" ").append(reg).append(", ").append(value).append("\n");
    }

    public void buildCal(String op, String reg1, String reg2, String reg3) {
        content.append("  ").append(op).append(" ").append(reg1).append(", ").append(reg2).append(", ").append(reg3).append("\n");
    }

    public void buildRet(String spSize) {
        content.append("  addi sp, sp, ").append(spSize).append("\n");
        content.append("  li a7, 93\n");
        content.append("  ecall\n");
    }

    public void buildLabel(String label) {
        content.append("\n").append(label).append(":\n");
    }

    public void buildBr(String op, String src1, String src2, String label) {
        content.append("  ").append(op).append(" ").append(src1).append(", ").append(src2).append(", ").append(label).append("\n");
    }

    public void buildJump(String label) {
        content.append("  ").append("j").append(" ").append(label).append("\n");
    }
}

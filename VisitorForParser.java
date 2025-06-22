import org.antlr.v4.runtime.tree.AbstractParseTreeVisitor;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.antlr.v4.runtime.tree.RuleNode;

public class VisitorForParser extends AbstractParseTreeVisitor<Void> {
    private int indentLevel = 0;
    private int[] oneSpaceFollow = new int[] { 1, 2, 3, 4, 5, 6, 9, 31 };
    private int[] towSpaceArround = new int[] { 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 23, 24 };
    private boolean spaceFollowFlag = false;
    private boolean spaceArroundFlag = false;
    private boolean isUnary = false;
    private boolean isReturn = false;
    private StringBuilder sb = new StringBuilder();

    private void applyIndent() {
        sb.append(" ".repeat(indentLevel * 4));
    }

    private void isSpaceFollow(int type) {
        for (int i : oneSpaceFollow) 
            if (i == type) spaceFollowFlag = true;
    }

    private void isSpaceArround(int type) {
        for (int i : towSpaceArround) 
            if (i == type) spaceArroundFlag = true;
    }

    public void Print() {
        System.out.println(sb.toString());
    }

    public int getChildIndex(ParseTree currentChild) {
        ParseTree parent = currentChild.getParent();
        if (parent == null) return -1;
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (parent.getChild(i) == currentChild) return i; 
        }
        return -1; 
    }

    @Override
    public Void visitTerminal(TerminalNode node) {
        int type = node.getSymbol().getType();
        spaceFollowFlag = false;
        spaceArroundFlag = false;
        if (type == SysYParser.WS || type == SysYParser.LINE_COMMENT || type == SysYParser.MULTILINE_COMMENT || type == SysYParser.EOF) {
            return null;
        }
        isSpaceArround(type);
        isSpaceFollow(type);
        if (spaceArroundFlag) {
            if (isUnary) {
                sb.append(node.getText());
                isUnary = false;
            } else sb.append(" " + node.getText() + " ");
        } else if (spaceFollowFlag) {
            if (type == SysYParser.RETURN) isReturn = true;
            sb.append(node.getText() + " ");
        } else {
            if (isReturn && type == SysYParser.SEMICOLON && sb.charAt(sb.length() - 1) == ' ') {
                sb.deleteCharAt(sb.length() - 1);
                sb.append(node.getText() + "\n");
                applyIndent();
                isReturn = false;
            } else if (type == SysYParser.SEMICOLON) {
                sb.append(node.getText() + "\n");
                applyIndent();
            }
            else sb.append(node.getText());
        }
        return null;
    }

    @Override
    public Void visitChildren(RuleNode node) {
        if (node instanceof SysYParser.FuncDefContext) {
            handleFuncDefFormat((SysYParser.FuncDefContext) node);
            return null;
        } else if (node instanceof SysYParser.BlockContext) {
            handleBlockFormat((SysYParser.BlockContext) node);
            return null;
        } else if (node instanceof SysYParser.StmtContext && (node.getChild(0).getText().equals("if") || node.getChild(0).getText().equals("while"))) {
            handleIfAndWhileFormat((SysYParser.StmtContext) node);
            return null;
        }
        
        for (int i = 0; i < node.getChildCount(); i++) {
            ParseTree child = node.getChild(i);
            if (child instanceof SysYParser.UnaryOpContext && !child.getText().equals("!")) {
                isUnary = true;
            }
            child.accept(this);
        }
        return null;
    }

    private void handleBlockFormat(SysYParser.BlockContext node) {
        sb.append("{\n");
        indentLevel++;
        applyIndent();
        for (int i = 1; i < node.getChildCount() - 1; i++) {
            ParseTree child = node.getChild(i);
            child.accept(this);
        }
        indentLevel--;
        sb.delete(sb.length() - 4, sb.length());
        sb.append("}\n");
        applyIndent();
    }

    private void handleFuncDefFormat(SysYParser.FuncDefContext node) {
        if (!sb.toString().isEmpty()) {
            sb.append("\n");
        }
        for (ParseTree child : node.children) {
            child.accept(this);
            if (child.getText().equals(")")) {
                sb.append(" ");
            }
        }
    }

    private void handleIfAndWhileFormat(SysYParser.StmtContext node) {
        for (ParseTree child : node.children) {
            boolean isBlock = child instanceof SysYParser.StmtContext && child.getChild(0) instanceof SysYParser.BlockContext;
            if (!isBlock && child instanceof SysYParser.StmtContext && !(child.getChild(0).getText().equals("if")
                    && child.getParent().getChild(getChildIndex(child) - 1).getText().equals("else"))) {
                sb.deleteCharAt(sb.length() - 1);
                sb.append("\n");
                indentLevel++;
                applyIndent();
            }
            child.accept(this);
            if (!isBlock && child instanceof SysYParser.StmtContext && !(child.getChild(0).getText().equals("if")
                    && child.getParent().getChild(getChildIndex(child) - 1).getText().equals("else"))) {
                sb.delete(sb.length() - 4, sb.length());
                indentLevel--;
            }
            if (child.getText().equals(")")) {
                sb.append(" ");
            } 
        }
    }
}
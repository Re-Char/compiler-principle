import org.llvm4j.llvm4j.Module;
import org.llvm4j.optional.Option;

import java.io.File;

public class OptimizedIR {
    private Module module;
    private ConstantSpread constantSpread;
    private DeleteUnused deleteUnused;
    private EliminateDead eliminateDead;

    public OptimizedIR(Module module) {
        this.module = module;
    }

    private void optimize() {
        boolean changed;
        // int iteration = 0;
        // final int MAX_ITERATIONS = 30;

        do {
            // iteration++;
            // System.out.println("\n===== 开始优化迭代 #" + iteration + " =====");
            constantSpread = new ConstantSpread(module);
            int constReplaced = constantSpread.optimize();
            // System.out.println("常量传播: 替换了 " + constReplaced + " 个常量");

            eliminateDead = new EliminateDead(module);
            int blocksEliminated = eliminateDead.optimize();
            // System.out.println("死代码消除: 处理了 " + blocksEliminated + " 个基本块");

            deleteUnused = new DeleteUnused(module);
            int varsDeleted = deleteUnused.optimize();
            // System.out.println("未使用变量消除: 删除了 " + varsDeleted + " 个变量");

            changed = (constReplaced > 0 || varsDeleted > 0 || blocksEliminated > 0);
            // System.out.println("迭代 #" + iteration + " " +
                    // (changed ? "产生了变化，继续优化" : "没有变化，优化完成"));

        } while (changed/* || iteration < MAX_ITERATIONS */);

        // System.out.println("\n优化完成，共执行 " + iteration + " 轮迭代");
    }

    public void generateOptimizedIR(File outputFile) {
        optimize();
        module.dump(Option.of(outputFile));
    }
}
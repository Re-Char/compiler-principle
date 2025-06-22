public class IntType extends Type {
    private static IntType intTypeInstance;

    private IntType() {
    }

    public static IntType getI32() {
        if (intTypeInstance == null) {
            intTypeInstance = new IntType();
        }
        return intTypeInstance;
    }

    @Override
    public String getTypeName() {
        return "INT";
    }
}
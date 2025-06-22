parser grammar SysYParser;

options {
    tokenVocab = SysYLexer;
}

program
   : compUnit
   ;

compUnit
   : (funcDef | decl)+ EOF
   ;

bType
   : INT
   ;

constInitVal
   : constExp   
   | L_BRACE (constInitVal (COMMA constInitVal)*)? R_BRACE
   ;

initVal
   : exp 
   | L_BRACE (initVal (COMMA initVal)*)? R_BRACE 
   ;

constDef
   : IDENT (L_BRACKT constExp R_BRACKT)* ASSIGN constInitVal 
   ;

varDef
   : IDENT (L_BRACKT constExp R_BRACKT)* 
   | IDENT (L_BRACKT constExp R_BRACKT)* ASSIGN initVal 
   ;

decl
   : CONST bType constDef (COMMA constDef)* SEMICOLON 
   | bType varDef (COMMA varDef)* SEMICOLON
   ;

funcDef
   : funcType IDENT L_PAREN funcFParams? R_PAREN block
   ; 

funcFParam
   : bType IDENT (L_BRACKT R_BRACKT (L_BRACKT exp R_BRACKT)*)?
   ;

funcFParams
   : funcFParam (COMMA funcFParam)*
   ;

funcType
    : INT
    | VOID
    ;

block
   : L_BRACE blockItem* R_BRACE
   ;

blockItem
   : decl 
   | stmt
   ;

stmt
   : lVal ASSIGN exp SEMICOLON
   | exp? SEMICOLON
   | block  
   | IF L_PAREN cond R_PAREN stmt (ELSE stmt)? 
   | WHILE L_PAREN cond R_PAREN stmt 
   | BREAK SEMICOLON    
   | CONTINUE SEMICOLON
   | RETURN exp? SEMICOLON
   ;

exp
   : L_PAREN exp R_PAREN
   | lVal 
   | number
   | IDENT L_PAREN funcRParams? R_PAREN 
   | unaryOp exp 
   | exp (MUL | DIV | MOD) exp
   | exp (PLUS | MINUS) exp
   ;

cond
   : exp 
   | cond (LT | GT | LE | GE) cond
   | cond (EQ | NEQ) cond 
   | cond AND cond 
   | cond OR cond 
   ;

lVal
   : IDENT (L_BRACKT exp R_BRACKT)*
   ;

number
   : INTEGER_CONST
   ;

unaryOp
   : PLUS
   | MINUS
   | NOT
   ;

funcRParams
   : param (COMMA param)*
   ;

param
   : exp
   ;

constExp
   : exp
   ;
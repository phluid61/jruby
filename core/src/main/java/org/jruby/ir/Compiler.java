/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.jruby.ir;

import org.jruby.Ruby;
import org.jruby.ast.executable.AbstractScript;
import org.jruby.ast.executable.Script;
import org.jruby.ast.executable.ScriptAndCode;
import org.jruby.compiler.NotCompilableException;
import org.jruby.ir.operands.IRException;
import org.jruby.ir.persistence.util.IRFileExpert;
import org.jruby.ir.runtime.IRBreakJump;
import org.jruby.ir.targets.JVM;
import org.jruby.ir.targets.JVMVisitor;
import org.jruby.ir.targets.JVMVisitorMethodContext;
import org.jruby.runtime.Block;
import org.jruby.runtime.Helpers;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ClassDefiningClassLoader;
import org.jruby.util.cli.Options;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

public class Compiler extends IRTranslator<ScriptAndCode, ClassDefiningClassLoader> {

    // Compiler is singleton
    private Compiler() {}

    private static class CompilerHolder {
        // FIXME: Remove as singleton unless lifus does later
        public static final Compiler instance = new Compiler();
    }

    public static Compiler getInstance() {
        return CompilerHolder.instance;
    }

    @Override
    protected ScriptAndCode execute(final Ruby runtime, final IRScriptBody scope, ClassDefiningClassLoader classLoader) {
        JVMVisitor visitor;
        Class compiled;
        byte[] bytecode;

        try {
            visitor = new JVMVisitor(runtime);
            JVMVisitorMethodContext context = new JVMVisitorMethodContext();
            bytecode = visitor.compileToBytecode(scope, context);
            compiled = visitor.defineFromBytecode(scope, bytecode, classLoader);
        } catch (NotCompilableException nce) {
            throw nce;
        }

        if (Options.COMPILE_CACHE_CLASSES.load()) {
            // write class to IR storage
            File path = IRFileExpert.getIRClassFile(JVM.scriptToClass(scope.getFile()));
            try (FileOutputStream fos = new FileOutputStream(path)) {
                fos.write(bytecode);

                System.err.println("saved compiled script as " + path);
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
        }

        Script script = getScriptFromClass(compiled);

        return new ScriptAndCode(bytecode, script);

    }

    public static Script getScriptFromClass(Class compiled) {
        MethodHandle _compiledHandle;
        try {
            Method compiledMethod = compiled.getMethod("run", ThreadContext.class, IRubyObject.class);
            _compiledHandle = MethodHandles.publicLookup().unreflect(compiledMethod);
        } catch (Throwable t) {
            throw new NotCompilableException("failed to load script from class" + compiled.getName(), t);
        }

        final MethodHandle compiledHandle = _compiledHandle;

        return new AbstractScript() {
            @Override
            public IRubyObject __file__(ThreadContext context, IRubyObject self, IRubyObject[] args, Block block) {
                try {
                    return (IRubyObject) compiledHandle.invokeWithArguments(context, self);
                } catch (Throwable t) {
                    Helpers.throwException(t);
                    return null; // not reached
                }
            }

            @Override
            public IRubyObject load(ThreadContext context, IRubyObject self, boolean wrap) {
                try {
                    return (IRubyObject) compiledHandle.invokeWithArguments(context, self);
                } catch (IRBreakJump bj) {
                    throw IRException.BREAK_LocalJumpError.getException(context.runtime);
                } catch (Throwable t) {
                    Helpers.throwException(t);
                    return null; // not reached
                } finally {
                }
            }
        };
    }

}

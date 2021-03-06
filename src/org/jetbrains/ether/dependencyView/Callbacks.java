package org.jetbrains.ether.dependencyView;

import org.objectweb.asm.ClassReader;

import java.util.Set;

/**
 * Created by IntelliJ IDEA.
 * User: db
 * Date: 28.01.11
 * Time: 15:55
 * To change this template use File | Settings | File Templates.
 */
public class Callbacks {
    public interface Backend {
        public void associate (String classFileName, String sourceFileName, ClassReader cr);
        public void associate (Set<ClassRepr> classes, Set<UsageRepr.Usage> usages, String sourceFileName);
    }
}

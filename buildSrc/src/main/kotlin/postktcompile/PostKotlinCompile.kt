/*
 * Copyright 2019-2021 Mamoe Technologies and contributors.
 *
 *  此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 *  Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 *  https://github.com/mamoe/mirai/blob/master/LICENSE
 */

package postktcompile

import analyzes.*
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.tasks.AbstractKotlinCompileTool
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import java.io.File

object PostKotlinCompile {
    private fun AsmClasses.canAccessInterface(from: ClassNode, to: String): Boolean {
        fun scan(current: ClassNode): Boolean {
            if (current.name == to) return true
            if (current.superName == to) return true
            current.interfaces?.forEach { itf ->
                if (scan(get(itf) ?: return@forEach)) return true
            }
            return false
        }
        return scan(from)
    }

    fun fixKtClass(dir: File, libs: Set<File>) = AsmUtil.run {
        val asmClasses: AsmClassesM = mutableMapOf()
        dir.walk().filter {
            it.isFile && it.extension == "class"
        }.forEach { f ->
            f.readClass().let { asmClasses[it.name] = it }
        }
        val classes = asmClasses.values.toList()
        libs.forEach { asmClasses += it.readLib() }

        classes.forEach { klass ->
            var edited = false
            klass.methods?.forEach { method ->
                method.instructions?.forEach { insn ->
                    if (insn is MethodInsnNode) {
                        if (insn.itf && insn.opcode != Opcodes.INVOKEINTERFACE && insn.opcode != Opcodes.INVOKESTATIC) {
                            if (!asmClasses.canAccessInterface(klass, insn.owner)) {
                                // println("${klass.name} . ${method.name}${method.desc}, ${insn.owner}.${insn.name}${insn.desc} (${insn.opcode})")
                                insn.opcode = Opcodes.INVOKEINTERFACE
                                edited = true
                            }
                        }
                    }
                }
            }
            if (edited) {
                dir.resolve("${klass.name}.class").writeBytes(
                    ClassWriter(0).also { klass.accept(it) }.toByteArray()
                )
            }
        }
    }

    fun registerForAll(project: Project) {
        project.subprojects {
            val subp: Project = this@subprojects
            subp.tasks.withType(AbstractKotlinCompileTool::class.java) {
                val task: AbstractKotlinCompileTool<*> = this@withType
                task.doLast {
                    task.outputs.files.forEach { f ->
                        fixKtClass(f, task.classpath.files)
                    }
                    println("$task: Kotlin compiled classes fix called.")
                }
            }
        }
    }
}
# The GraalVM LLVM Toolchain

The *toolchain* is a set of tools and APIs for compiling native projects, such as C and C++,
to bitcode that can be executed with the GraalVM LLVM runtime.
It is aimed to simplify ahead-of-time compilation for users
and language implementers who want to use the GraalVM LLVM runtime.

## Use Cases

1. **Simplify compilation to bitcode**
  *GraalVM users* who want to run native projects via the GraalVM LLVM runtime
  must first compile these projects to LLVM bitcode.
  Although it is possible to do this with the standard LLVM tools (`clang`, `llvm-link`, etc.),
  there are several additional considerations, such as optimizations and manual linking.
  The toolchain aims to simplify this process, by providing an out-of-the-box drop-in replacement for the compiler
  when building native projects targeting the GraalVM LLVM runtime.

2. **Compilation of native extensions**
  *GraalVM language implementers* often use the GraalVM LLVM runtime to execute native extensions, 
  and these extensions are commonly installed by a package manager. 
  For example, packages in python are usually added via `pip install`, which means that the python
  implementation is required to be able to compile these native extensions on demand.
  The toolchain provides a Java API for languages to access the tools currently (optionally) bundled with GraalVM.

3. **Compiling to bitcode at build time**
  *GraalVM languages* that integrate with the GraalVM LLVM runtime usually need to build
  bitcode libraries to integrate with the native pieces of their implementation.
  The toolchain can be used as a build-time dependency to achieve this in a
  standardized and compatible way.

## File Format

To be compatible with existing build systems, by default, the toolchain will
produce native executables with embedded bitcode (ELF files on Linux, Mach-O
files on MacOS).

## Toolchain Identifier

The GraalVM LLVM runtime can be ran in different configurations, which can differ in how the bitcode is being compiled.
Generally, users of the toolchain do not need to be concerned, as the GraalVM LLVM runtime knows the mode it is running
and will always provide the right toolchain.
However, if a language implementation wants to store the
bitcode compilation for later use, it will need to be able to identify the toolchain and its configurations used to compile the bitcode.
To do so, each toolchain has an *identifier*.
Conventionally, the identifier denotes the compilation output directory.
The internal GraalVM LLVM runtime library layout follows the same approach.

## Java API

Language implementations can access the toolchain via the [`Toolchain`](../../sulong/projects/com.oracle.truffle.llvm.api/src/com/oracle/truffle/llvm/api/Toolchain.java) service.
The service provides two methods:

* `TruffleFile getToolPath(String tool)`
  Returns the path to the executable for a given tool.
  Every implementation is free to choose its own set of supported tools.
  The command line interface of the executable is specific to the tool.
  If a tool is not supported or not known, `null` is returned.
  Consult the Javadoc for a list known tools.
* `String getIdentifier()`
  Returns the identifier for the toolchain.
  It can be used to distinguish results produced by different toolchains.
  The identifier can be used as a path suffix to place results in distinct locations,
  therefore it does not contain special characters like slashes or spaces.

The `Toolchain` lives in the `SULONG_API` distribution.
The LLVM runtime will always provide a toolchain that matches its current mode.
The service can be looked-up via the `Env`:

```Java
LanguageInfo llvmInfo = env.getInternalLanguages().get("llvm");
Toolchain toolchain = env.lookup(llvmInfo, Toolchain.class);
TruffleFile toolPath = toolchain.getToolPath("CC");
String toolchainId = toolchain.getIdentifier();
```

## `mx` integration

On the `mx` side, the toolchain can be accessed via the *substitutions* `toolchainGetToolPath` and `toolchainGetIdentifier`.
Note that they expect a toolchain name as the first argument. See for example the following snippet from a `suite.py` file:

```python
  "buildEnv" : {
    "CC": "<toolchainGetToolPath:native,CC>",
    "CXX": "<toolchainGetToolPath:native,CXX>",
    "PLATFORM": "<toolchainGetIdentifier:native>",
  },
```

## GraalVM Deployment

On the implementation side, _the toolchain_ consists of multiple ingredients:

* The **LLVM.org component** is similar to a regular [LLVM release](https://llvm.org) (clang, lld, llvm-* tools)
  but includes [a few patches](../patches) that are not yet [upstream](https://github.com/llvm/llvm-project).
  Those patches are general feature improvements that are not specific to GraalVM.
  In GraalVM, the LLVM.org component is located in `$GRAALVM/jre/lib/llvm/`.
  This component is considered as internal and should not be directly used.
  The LLVM.org component might not be installed by default. If that is the case, it can be installed via `gu install llvm-toolchain`.
* The **toolchain wrappers** are GraalVM launchers that invoke the tools from the LLVM.org component with special flags
  to produce results that can be executed by the GraalVM LLVM runtime. The Java and `mx` APIs return paths to those wrappers.
  In GraalVM, the wrappers live in `$GRAALVM/jre/languages/llvm/$TOOLCHAIN_ID/bin/`. The wrappers are shipped with the
  GraalVM LLVM runtime and do not need to be installed separately.
  They are meant to be drop in replacements for the C/C++ compiler when compiling a native project.
  The goal is to produce a GraalVM LLVM runtime executable result by simply pointing any build system to those wrappers,
  for example via `CC`/`CXX` environment variables or by setting `PATH`.

## Bootstrapping Toolchain

During building, the LLVM.org component is available in the `mxbuild/SULONG_LLVM_ORG` distribution.
Bootstrapping wrappers can be found in the `mxbuild/SULONG_BOOTSTRAP_TOOLCHAIN` distribution.
However, the APIs will take care of providing the right one.
These distributions are for manual usage only and are considered unstable and might change without notice.
Do not depend on them.

### Using a prebuilt GraalVM as a Bootstrapping Toolchain

To speed up toolchain compilation during development, the `SULONG_BOOTSTRAP_GRAALVM` environment variable can be set
to a _prebuilt_ GraalVM. Sulong comes with a configuration file that makes building a bootstrapping GraalVM easy:

```bash
$ mx --env toolchain-only build
$ export SULONG_BOOTSTRAP_GRAALVM=`mx --env toolchain-only graalvm-home`
```
> **WARNING**: *The bootstrapping GraalVM will not be rebuilt automatically. You are responsible for keeping it up-to-date.*

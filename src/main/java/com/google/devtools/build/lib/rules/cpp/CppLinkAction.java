// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.rules.cpp;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandAction;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionInfoSpecifier;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.SimpleSpawn;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.extra.CppLinkInfo;
import com.google.devtools.build.lib.actions.extra.ExtraActionInfo;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.rules.cpp.CcLinkParams.Linkstamp;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration.Tool;
import com.google.devtools.build.lib.rules.cpp.Link.LinkStaticness;
import com.google.devtools.build.lib.rules.cpp.Link.LinkTargetType;
import com.google.devtools.build.lib.rules.cpp.LinkerInputs.LibraryToLink;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.ShellEscaper;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import javax.annotation.Nullable;

/** 
 * Action that represents a linking step. 
 */
@ThreadCompatible
public final class CppLinkAction extends AbstractAction
    implements ExecutionInfoSpecifier, CommandAction {

  /**
   * An abstraction for creating intermediate and output artifacts for C++ linking.
   *
   * <p>This is unfortunately necessary, because most of the time, these artifacts are well-behaved
   * ones sitting under a package directory, but nativedeps link actions can be shared. In order to
   * avoid creating every artifact here with {@code getShareableArtifact()}, we abstract the
   * artifact creation away.
   */
  public interface LinkArtifactFactory {
    /**
     * Create an artifact at the specified root-relative path in the bin directory.
     */
    Artifact create(RuleContext ruleContext, BuildConfiguration configuration,
                    PathFragment rootRelativePath);
  }

  /**
   * An implementation of {@link LinkArtifactFactory} that can only create artifacts in the package
   * directory.
   */
  public static final LinkArtifactFactory DEFAULT_ARTIFACT_FACTORY = new LinkArtifactFactory() {
    @Override
    public Artifact create(RuleContext ruleContext, BuildConfiguration configuration,
                           PathFragment rootRelativePath) {
      return ruleContext.getDerivedArtifact(
          rootRelativePath, configuration.getBinDirectory(ruleContext.getRule().getRepository()));
    }
  };

  private static final String LINK_GUID = "58ec78bd-1176-4e36-8143-439f656b181d";
  private static final String FAKE_LINK_GUID = "da36f819-5a15-43a9-8a45-e01b60e10c8b";
  
  @Nullable private final String mnemonic;
  private final CppConfiguration cppConfiguration;
  private final LibraryToLink outputLibrary;
  private final Artifact linkOutput;
  private final LibraryToLink interfaceOutputLibrary;
  private final ImmutableSet<String> clientEnvironmentVariables;
  private final ImmutableMap<String, String> actionEnv;
  private final ImmutableMap<String, String> toolchainEnv;
  private final ImmutableSet<String> executionRequirements;
  private final ImmutableList<Artifact> linkstampObjects;

  private final LinkCommandLine linkCommandLine;

  /** True for cc_fake_binary targets. */
  private final boolean fake;
  private final boolean isLtoIndexing;

  private final PathFragment ldExecutable;
  private final String hostSystemName;
  private final String targetCpu;

  private final Iterable<Artifact> mandatoryInputs;

  // Linking uses a lot of memory; estimate 1 MB per input file, min 1.5 Gib.
  // It is vital to not underestimate too much here,
  // because running too many concurrent links can
  // thrash the machine to the point where it stops
  // responding to keystrokes or mouse clicks.
  // CPU and IO do not scale similarly and still use the static minimum estimate.
  public static final ResourceSet LINK_RESOURCES_PER_INPUT =
      ResourceSet.createWithRamCpuIo(1, 0, 0);

  // This defines the minimum of each resource that will be reserved.
  public static final ResourceSet MIN_STATIC_LINK_RESOURCES =
      ResourceSet.createWithRamCpuIo(1536, 1, 0.3);

  // Dynamic linking should be cheaper than static linking.
  public static final ResourceSet MIN_DYNAMIC_LINK_RESOURCES =
      ResourceSet.createWithRamCpuIo(1024, 0.3, 0.2);

  /**
   * Use {@link CppLinkActionBuilder} to create instances of this class. Also see there for the
   * documentation of all parameters.
   *
   * <p>This constructor is intentionally private and is only to be called from {@link
   * CppLinkActionBuilder#build()}.
   */
  CppLinkAction(
      ActionOwner owner,
      String mnemonic,
      Iterable<Artifact> inputs,
      ImmutableList<Artifact> outputs,
      CppConfiguration cppConfiguration,
      LibraryToLink outputLibrary,
      Artifact linkOutput,
      LibraryToLink interfaceOutputLibrary,
      boolean fake,
      boolean isLtoIndexing,
      ImmutableList<Artifact> linkstampObjects,
      LinkCommandLine linkCommandLine,
      ImmutableSet<String> clientEnvironmentVariables,
      ImmutableMap<String, String> actionEnv,
      ImmutableMap<String, String> toolchainEnv,
      ImmutableSet<String> executionRequirements,
      CcToolchainProvider toolchain) {
    super(owner, inputs, outputs);
    if (mnemonic == null) {
      this.mnemonic = (isLtoIndexing) ? "CppLTOIndexing" : "CppLink";
    } else {
      this.mnemonic = mnemonic;
    }
    this.mandatoryInputs = inputs;
    this.cppConfiguration = cppConfiguration;
    this.outputLibrary = outputLibrary;
    this.linkOutput = linkOutput;
    this.interfaceOutputLibrary = interfaceOutputLibrary;
    this.fake = fake;
    this.isLtoIndexing = isLtoIndexing;
    this.linkstampObjects = linkstampObjects;
    this.linkCommandLine = linkCommandLine;
    this.clientEnvironmentVariables = clientEnvironmentVariables;
    this.actionEnv = actionEnv;
    this.toolchainEnv = toolchainEnv;
    this.executionRequirements = executionRequirements;
    this.ldExecutable = toolchain.getToolPathFragment(Tool.LD);
    this.hostSystemName = toolchain.getHostSystemName();
    this.targetCpu = toolchain.getTargetCpu();
  }

  private CppConfiguration getCppConfiguration() {
    return cppConfiguration;
  }

  @VisibleForTesting
  public String getTargetCpu() {
    return targetCpu;
  }

  public String getHostSystemName() {
    return hostSystemName;
  }

  @Override
  @VisibleForTesting
  public Iterable<Artifact> getPossibleInputsForTesting() {
    return getInputs();
  }

  @Override
  public Iterable<String> getClientEnvironmentVariables() {
    return clientEnvironmentVariables;
  }

  @Override
  public ImmutableMap<String, String> getEnvironment() {
    LinkedHashMap<String, String> result = new LinkedHashMap<>();

    result.putAll(actionEnv);
    result.putAll(toolchainEnv);

    if (!executionRequirements.contains(ExecutionRequirements.REQUIRES_DARWIN)) {
      // This prevents gcc from writing the unpredictable (and often irrelevant)
      // value of getcwd() into the debug info.
      result.put("PWD", "/proc/self/cwd");
    }
    return ImmutableMap.copyOf(result);
  }

  /**
   * Returns the link configuration; for correctness you should not call this method during
   * execution - only the argv is part of the action cache key, and we therefore don't guarantee
   * that the action will be re-executed if the contents change in a way that does not affect the
   * argv.
   */
  @VisibleForTesting
  public LinkCommandLine getLinkCommandLine() {
    return linkCommandLine;
  }

  /**
   * Returns the output of this action as a {@link LibraryToLink} or null if it is an executable.
   */
  @Nullable
  public LibraryToLink getOutputLibrary() {
    return outputLibrary;
  }

  public LibraryToLink getInterfaceOutputLibrary() {
    return interfaceOutputLibrary;
  }

  /**
   * Returns the output of the linking.
   */
  public Artifact getLinkOutput() {
    return linkOutput;
  }

  /**
   * Returns the path to the output artifact produced by the linker.
   */
  public Path getOutputFile() {
    return linkOutput.getPath();
  }

  @Override
  public ImmutableMap<String, String> getExecutionInfo() {
    ImmutableMap.Builder<String, String> result = ImmutableMap.<String, String>builder();
    for (String requirement : executionRequirements) {
      result.put(requirement, "");
    }
    return result.build();
  }
  
  @Override
  public List<String> getArguments() {
    return linkCommandLine.arguments();
  }

  /**
   * Returns the command line specification for this link, included any required linkstamp
   * compilation steps. The command line may refer to a .params file.
   *
   * @return a finalized command line suitable for execution
   */
  public final List<String> getCommandLine() {
    return linkCommandLine.getCommandLine();
  }

  /**
   * Returns a (possibly empty) list of linkstamp object files.
   *
   * <p>This is used to embed various values from the build system into binaries to identify their
   * provenance.
   */
  public ImmutableList<Artifact> getLinkstampObjects() {
    return linkstampObjects;
  }

  @Override
  @ThreadCompatible
  public ActionResult execute(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    if (fake) {
      executeFake();
      return ActionResult.EMPTY;
    } else {
      try {
        Spawn spawn = new SimpleSpawn(
            this,
            ImmutableList.copyOf(getCommandLine()),
            getEnvironment(),
            getExecutionInfo(),
            ImmutableList.copyOf(getMandatoryInputs()),
            getOutputs().asList(),
            estimateResourceConsumptionLocal());
        return ActionResult.create(
            actionExecutionContext
                .getSpawnActionContext(getMnemonic())
                .exec(spawn, actionExecutionContext));
      } catch (ExecException e) {
        throw e.toActionExecutionException(
            "Linking of rule '" + getOwner().getLabel() + "'",
            actionExecutionContext.getVerboseFailures(),
            this);
      }
    }
  }

  // Don't forget to update FAKE_LINK_GUID if you modify this method.
  @ThreadCompatible
  private void executeFake()
      throws ActionExecutionException {
    // Prefix all fake output files in the command line with $TEST_TMPDIR/.
    final String outputPrefix = "$TEST_TMPDIR/";
    List<String> escapedLinkArgv = escapeLinkArgv(linkCommandLine.getRawLinkArgv(), outputPrefix);
    // Write the commands needed to build the real target to the fake target
    // file.
    StringBuilder s = new StringBuilder();
    Joiner.on('\n').appendTo(s,
        "# This is a fake target file, automatically generated.",
        "# Do not edit by hand!",
        "echo $0 is a fake target file and not meant to be executed.",
        "exit 0",
        "EOS",
        "",
        "makefile_dir=.",
        "");

    try {
      // Concatenate all the (fake) .o files into the result.
      for (LinkerInput linkerInput : getLinkCommandLine().getLinkerInputs()) {
        Artifact objectFile = linkerInput.getArtifact();
        if ((CppFileTypes.OBJECT_FILE.matches(objectFile.getFilename())
                || CppFileTypes.PIC_OBJECT_FILE.matches(objectFile.getFilename()))
            && linkerInput.isFake()) {
          s.append(FileSystemUtils.readContentAsLatin1(objectFile.getPath())); // (IOException)
        }
      }

      s.append(getOutputFile().getBaseName()).append(": ");
      Joiner.on(' ').appendTo(s, escapedLinkArgv);
      s.append('\n');
      if (getOutputFile().exists()) {
        getOutputFile().setWritable(true); // (IOException)
      }
      FileSystemUtils.writeContent(getOutputFile(), ISO_8859_1, s.toString());
      getOutputFile().setExecutable(true); // (IOException)
    } catch (IOException e) {
      throw new ActionExecutionException("failed to create fake link command for rule '"
                                         + getOwner().getLabel() + ": " + e.getMessage(),
                                         this, false);
    }
  }

  /**
   * Shell-escapes the raw link command line.
   *
   * @param rawLinkArgv raw link command line
   * @param outputPrefix to be prepended to any outputs
   * @return escaped link command line
   */
  private List<String> escapeLinkArgv(List<String> rawLinkArgv, String outputPrefix) {
    ImmutableList.Builder<String> escapedArgs = ImmutableList.builder();
    for (String rawArg : rawLinkArgv) {
      String escapedArg;
      if (rawArg.equals(getPrimaryOutput().getExecPathString())) {
        escapedArg = outputPrefix + ShellEscaper.escapeString(rawArg);
      } else if (rawArg.startsWith(Link.FAKE_OBJECT_PREFIX)) {
        escapedArg =
            outputPrefix
                + ShellEscaper.escapeString(rawArg.substring(Link.FAKE_OBJECT_PREFIX.length()));
      } else {
        escapedArg = ShellEscaper.escapeString(rawArg);
      }
      escapedArgs.add(escapedArg);
    }
    return escapedArgs.build();
  }

  @Override
  public ExtraActionInfo.Builder getExtraActionInfo(ActionKeyContext actionKeyContext) {
    // The uses of getLinkConfiguration in this method may not be consistent with the computed key.
    // I.e., this may be incrementally incorrect.
    CppLinkInfo.Builder info = CppLinkInfo.newBuilder();
    info.addAllInputFile(Artifact.toExecPaths(
        LinkerInputs.toLibraryArtifacts(getLinkCommandLine().getLinkerInputs())));
    info.addAllInputFile(Artifact.toExecPaths(
        LinkerInputs.toLibraryArtifacts(getLinkCommandLine().getRuntimeInputs())));
    info.setOutputFile(getPrimaryOutput().getExecPathString());
    if (interfaceOutputLibrary != null) {
      info.setInterfaceOutputFile(interfaceOutputLibrary.getArtifact().getExecPathString());
    }
    info.setLinkTargetType(getLinkCommandLine().getLinkTargetType().name());
    info.setLinkStaticness(getLinkCommandLine().getLinkStaticness().name());
    info.addAllLinkStamp(Artifact.toExecPaths(getLinkstampObjects()));
    info.addAllBuildInfoHeaderArtifact(Artifact.toExecPaths(getBuildInfoHeaderArtifacts()));
    info.addAllLinkOpt(getLinkCommandLine().getRawLinkArgv());

    try {
      return super.getExtraActionInfo(actionKeyContext)
          .setExtension(CppLinkInfo.cppLinkInfo, info.build());
    } catch (CommandLineExpansionException e) {
      throw new AssertionError("CppLinkAction command line expansion cannot fail.");
    }
  }

  /** Returns the (ordered, immutable) list of header files that contain build info. */
  public Iterable<Artifact> getBuildInfoHeaderArtifacts() {
    return linkCommandLine.getBuildInfoHeaderArtifacts();
  }

  @Override
  protected String computeKey(ActionKeyContext actionKeyContext) {
    Fingerprint f = new Fingerprint();
    f.addString(fake ? FAKE_LINK_GUID : LINK_GUID);
    f.addString(ldExecutable.getPathString());
    f.addStrings(linkCommandLine.arguments());
    f.addStrings(getExecutionInfo().keySet());

    // TODO(bazel-team): For correctness, we need to ensure the invariant that all values accessed
    // during the execution phase are also covered by the key. Above, we add the argv to the key,
    // which covers most cases. Unfortunately, the extra action and fake support methods above also
    // sometimes directly access settings from the link configuration that may or may not affect the
    // key. We either need to change the code to cover them in the key computation, or change the
    // LinkConfiguration to disallow the combinations where the value of a setting does not affect
    // the argv.
    f.addBoolean(linkCommandLine.isNativeDeps());
    f.addBoolean(linkCommandLine.useTestOnlyFlags());
    if (linkCommandLine.getRuntimeSolibDir() != null) {
      f.addPath(linkCommandLine.getRuntimeSolibDir());
    }
    f.addBoolean(isLtoIndexing);
    return f.hexDigestAndReset();
  }

  @Override
  public String describeKey() {
    StringBuilder message = new StringBuilder();
    if (fake) {
      message.append("Fake ");
    }
    message.append(getProgressMessage());
    message.append('\n');
    message.append("  Command: ");
    message.append(
        ShellEscaper.escapeString(
            getCppConfiguration().getToolPathFragment(Tool.LD).getPathString()));
    message.append('\n');
    // Outputting one argument per line makes it easier to diff the results.
    for (String argument : ShellEscaper.escapeAll(linkCommandLine.arguments())) {
      message.append("  Argument: ");
      message.append(argument);
      message.append('\n');
    }
    return message.toString();
  }

  @Override
  public String getMnemonic() {
    return mnemonic;
  }

  @Override
  protected String getRawProgressMessage() {
    return (isLtoIndexing ? "LTO indexing " : "Linking ") + linkOutput.prettyPrint();
  }

  /**
   * Estimate the resources consumed when this action is run locally.
   */
  public ResourceSet estimateResourceConsumptionLocal() {
    // It's ok if this behaves differently even if the key is identical.
    ResourceSet minLinkResources =
        getLinkCommandLine().getLinkStaticness() == Link.LinkStaticness.DYNAMIC
        ? MIN_DYNAMIC_LINK_RESOURCES
        : MIN_STATIC_LINK_RESOURCES;

    final int inputSize = Iterables.size(getLinkCommandLine().getLinkerInputs())
        + Iterables.size(getLinkCommandLine().getRuntimeInputs());

    return ResourceSet.createWithRamCpuIo(
        Math.max(inputSize * LINK_RESOURCES_PER_INPUT.getMemoryMb(),
            minLinkResources.getMemoryMb()),
        Math.max(inputSize * LINK_RESOURCES_PER_INPUT.getCpuUsage(),
            minLinkResources.getCpuUsage()),
        Math.max(inputSize * LINK_RESOURCES_PER_INPUT.getIoUsage(),
            minLinkResources.getIoUsage())
    );
  }

  @Override
  public Iterable<Artifact> getMandatoryInputs() {
    return mandatoryInputs;
  }

  /** Determines whether or not this link should output a symbol counts file. */
  public static boolean enableSymbolsCounts(
      CppConfiguration cppConfiguration,
      boolean supportsGoldLinker,
      boolean fake,
      LinkTargetType linkType) {
    return cppConfiguration.getSymbolCounts()
        && supportsGoldLinker
        && linkType == LinkTargetType.EXECUTABLE
        && !fake;
  }

  public static PathFragment symbolCountsFileName(PathFragment binaryName) {
    return binaryName.replaceName(binaryName.getBaseName() + ".sc");
  }

  /**
   * TransitiveInfoProvider for ELF link actions.
   */
  @Immutable @ThreadSafe
  public static final class Context implements TransitiveInfoProvider {
    // Morally equivalent with {@link Builder}, except these are immutable.
    // Keep these in sync with {@link Builder}.
    final ImmutableSet<LinkerInput> objectFiles;
    final ImmutableSet<Artifact> nonCodeInputs;
    final NestedSet<LibraryToLink> libraries;
    final NestedSet<Artifact> crosstoolInputs;
    final ImmutableMap<Artifact, Artifact> ltoBitcodeFiles;
    final Artifact runtimeMiddleman;
    final NestedSet<Artifact> runtimeInputs;
    final ArtifactCategory runtimeType;
    final ImmutableSet<Linkstamp> linkstamps;
    final ImmutableList<String> linkopts;
    final LinkTargetType linkType;
    final LinkStaticness linkStaticness;
    final boolean fake;
    final boolean isNativeDeps;
    final boolean useTestOnlyFlags;

    /**
     * Given a {@link CppLinkActionBuilder}, creates a {@code Context} to pass to another target.
     * Note well: Keep the Builder->Context and Context->Builder transforms consistent!
     *
     * @param builder a mutable {@link CppLinkActionBuilder} to clone from
     */
    public Context(CppLinkActionBuilder builder) {
      this.objectFiles = ImmutableSet.copyOf(builder.getObjectFiles());
      this.nonCodeInputs = ImmutableSet.copyOf(builder.getNonCodeInputs());
      this.libraries = NestedSetBuilder.<LibraryToLink>linkOrder()
          .addTransitive(builder.getLibraries().build()).build();
      this.crosstoolInputs =
          NestedSetBuilder.<Artifact>stableOrder().addTransitive(builder.getCrosstoolInputs()).build();
      this.ltoBitcodeFiles = ImmutableMap.copyOf(builder.getLtoBitcodeFiles());
      this.runtimeMiddleman = builder.getRuntimeMiddleman();
      this.runtimeInputs =
          NestedSetBuilder.<Artifact>stableOrder().addTransitive(builder.getRuntimeInputs()).build();
      this.runtimeType = builder.getRuntimeType();
      this.linkstamps = builder.getLinkstamps();
      this.linkopts = ImmutableList.copyOf(builder.getLinkopts());
      this.linkType = builder.getLinkType();
      this.linkStaticness = builder.getLinkStaticness();
      this.fake = builder.isFake();
      this.isNativeDeps = builder.isNativeDeps();
      this.useTestOnlyFlags = builder.useTestOnlyFlags();
    }

    /**
     * Returns linker inputs that are not libraries.
     */
    public ImmutableSet<LinkerInput> getObjectFiles() {
      return this.objectFiles;
    }
    
    /**
     * Returns libraries that are to be inputs to the linker.
     */
    public NestedSet<LibraryToLink> getLibraries() {
      return this.libraries;
    }
    
    /**
     * Returns input artifacts arising from the crosstool.
     */
    public NestedSet<Artifact> getCrosstoolInputs() {
      return this.crosstoolInputs;
    }
    
    /**
     * Returns the runtime middleman artifact.
     */
    public Artifact getRuntimeMiddleman() {
      return this.runtimeMiddleman;
    }
    
    /**
     * Returns runtime inputs for the linker.
     */
    public NestedSet<Artifact> getRuntimeInputs() {
      return this.runtimeInputs;
    }

    /** Returns linkstamp artifacts. */
    public ImmutableSet<Linkstamp> getLinkstamps() {
      return this.linkstamps;
    }

    /**
     * Returns linkopts for the linking of this target.
     */
    public ImmutableList<String> getLinkopts() {
      return this.linkopts;
    }
    
    /**
     * Returns the type of the linking of this target.
     */
    public LinkTargetType getLinkType() {
      return this.linkType;
    }
    
    /**
     * Returns the staticness of the linking of this target.
     */
    public LinkStaticness getLinkStaticness() {
      return this.linkStaticness;
    }
    
    /**
     * Returns true for cc_fake_binary targets.
     */
    public boolean isFake() {
      return this.fake;
    }
    
    /**
     * Returns true if the linking of this target is used for a native dependency library.
     */
    public boolean isNativeDeps() {
      return this.isNativeDeps;
    }
    
    /**
     * Returns true if the linking for this target uses test-specific flags.
     */
    public boolean useTestOnlyFlags() {
      return this.useTestOnlyFlags;
    }
  }
}

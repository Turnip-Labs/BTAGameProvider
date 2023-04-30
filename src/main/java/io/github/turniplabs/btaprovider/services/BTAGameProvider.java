package io.github.turniplabs.btaprovider.services;

import io.github.turniplabs.btaprovider.patch.EntrypointPatch;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.ObjectShare;
import net.minecraft.core.Minecraft;
import org.quiltmc.loader.api.ModDependency;
import org.quiltmc.loader.api.ModDependencyIdentifier;
import org.quiltmc.loader.api.Version;
import org.quiltmc.loader.api.VersionRange;
import org.quiltmc.loader.impl.FormattedException;
import org.quiltmc.loader.impl.QuiltLoaderImpl;
import org.quiltmc.loader.impl.entrypoint.GameTransformer;
import org.quiltmc.loader.impl.game.GameProvider;
import org.quiltmc.loader.impl.game.GameProviderHelper;
import org.quiltmc.loader.impl.game.LibClassifier;
import org.quiltmc.loader.impl.launch.common.QuiltLauncher;
import org.quiltmc.loader.impl.metadata.qmj.V1ModMetadataBuilder;
import org.quiltmc.loader.impl.util.Arguments;
import org.quiltmc.loader.impl.util.ExceptionUtil;
import org.quiltmc.loader.impl.util.LoaderUtil;
import org.quiltmc.loader.impl.util.SystemProperties;
import org.quiltmc.loader.impl.util.log.Log;
import org.quiltmc.loader.impl.util.log.LogCategory;
import org.quiltmc.loader.impl.util.log.LogHandler;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class BTAGameProvider implements GameProvider {
	private static final String[] ALLOWED_EARLY_CLASS_PREFIXES = { "org.apache.logging.log4j." };

	private static final Set<String> SENSITIVE_ARGS = new HashSet<>(Arrays.asList(
			"accesstoken",
			"clientid",
			"profileproperties",
			"proxypass",
			"proxyuser",
			"username",
			"userproperties",
			"uuid",
			"xuid"));

	private EnvType envType;
	private String entrypoint;
	private Arguments arguments;
	private final List<Path> gameJars = new ArrayList<>(2); // env game jar and common game jar, potentially
	private final Set<Path> logJars = new HashSet<>();
	private boolean log4jAvailable;
	private boolean slf4jAvailable;
	private final List<Path> miscGameLibraries = new ArrayList<>(); // libraries not relevant for loader's uses
	private String gameVersion = Minecraft.VERSION.toLowerCase(Locale.ROOT).replace(' ', '-');
	private boolean hasModLoader = false;

	private final GameTransformer transformer = new GameTransformer(
			new EntrypointPatch());

	@Override
	public String getGameId() {
		return "bta";
	}

	@Override
	public String getGameName() {
		return "Better than Adventure!";
	}

	@Override
	public String getRawGameVersion() {
		return gameVersion;
	}

	@Override
	public String getNormalizedGameVersion() {
		return getRawGameVersion();
	}

	@Override
	public Collection<BuiltinMod> getBuiltinMods() {
		V1ModMetadataBuilder metadata = new V1ModMetadataBuilder();
		metadata.id = getGameId();
		metadata.group = "builtin";
		metadata.version = Version.of(getNormalizedGameVersion());
		metadata.name = getGameName();

		Version minJava = Version.of(Integer.toString(8));
		VersionRange range = VersionRange.ofInterval(minJava, true, null, false);
		metadata.depends.add(new ModDependency.Only() {
				@Override
				public boolean shouldIgnore() {
					return false;
				}

				@Override
				public VersionRange versionRange() {
					return range;
				}

				@Override
				public ModDependency unless() {
					return null;
				}

				@Override
				public String reason() {
					return "";
				}

				@Override
				public boolean optional() {
					return false;
				}

				@Override
				public ModDependencyIdentifier id() {
					return new ModDependencyIdentifier() {
						@Override
						public String mavenGroup() {
							return "";
						}

						@Override
						public String id() {
							return "java";
						}
					};
				}
		});

		return Collections.singletonList(new BuiltinMod(gameJars, metadata.build()));
	}

	public Path getGameJar() {
		return gameJars.get(0);
	}

	@Override
	public String getEntrypoint() {
		return entrypoint;
	}

	@Override
	public Path getLaunchDirectory() {
		if (arguments == null) {
			return Paths.get(".");
		}

		return getLaunchDirectory(arguments);
	}

	@Override
	public boolean isObfuscated() {
		return false;
	}

	@Override
	public boolean requiresUrlClassLoader() {
		return hasModLoader;
	}

	@Override
	public boolean isEnabled() {
		return true;
	}


	@Override
	public boolean locateGame(QuiltLauncher launcher, String[] args) {
		this.envType = launcher.getEnvironmentType();
		this.arguments = new Arguments();
		arguments.parse(args);

		try {
			LibClassifier<McLibrary> classifier = new LibClassifier<>(McLibrary.class, envType, this);
			McLibrary envGameLib = envType == EnvType.CLIENT ? McLibrary.MC_CLIENT : McLibrary.MC_SERVER;
			Path commonGameJar = GameProviderHelper.getCommonGameJar();
			Path envGameJar = GameProviderHelper.getEnvGameJar(envType);
			boolean commonGameJarDeclared = commonGameJar != null;

			if (commonGameJarDeclared) {
				if (envGameJar != null) {
					classifier.process(envGameJar, McLibrary.MC_COMMON);
				}

				classifier.process(commonGameJar);
			} else if (envGameJar != null) {
				classifier.process(envGameJar);
			}

			Set<Path> classpath = new LinkedHashSet<>();

			for (Path path : launcher.getClassPath()) {
				path = LoaderUtil.normalizeExistingPath(path);
				classpath.add(path);
				classifier.process(path);
			}

			envGameJar = classifier.getOrigin(envGameLib);
			if (envGameJar == null) return false;

			commonGameJar = classifier.getOrigin(McLibrary.MC_COMMON);

			if (commonGameJarDeclared && commonGameJar == null) {
				Log.warn(LogCategory.GAME_PROVIDER, "The declared common game jar didn't contain any of the expected classes!");
			}

			gameJars.add(envGameJar);

			if (commonGameJar != null && !commonGameJar.equals(envGameJar)) {
				gameJars.add(commonGameJar);
			}

			entrypoint = classifier.getClassName(envGameLib);
			log4jAvailable = classifier.has(McLibrary.LOG4J_API) && classifier.has(McLibrary.LOG4J_CORE);
			slf4jAvailable = classifier.has(McLibrary.SLF4J_API) && classifier.has(McLibrary.SLF4J_CORE);
			boolean hasLogLib = log4jAvailable || slf4jAvailable;

			Log.configureBuiltin(hasLogLib, !hasLogLib);

			for (McLibrary lib : McLibrary.LOGGING) {
				Path path = classifier.getOrigin(lib);

				if (path != null && !classpath.contains(path)) {
					if (hasLogLib) {
						logJars.add(path);
					} else if (!gameJars.contains(path)) {
						miscGameLibraries.add(path);
					}
				}
			}

			for (Path path : classifier.getUnmatchedOrigins()) {
				if (!classpath.contains(path)) {
					miscGameLibraries.add(path);
				}
			}
		} catch (IOException e) {
			throw ExceptionUtil.wrap(e);
		}

		ObjectShare share = QuiltLoaderImpl.INSTANCE.getObjectShare();
		share.put("fabric-loader:inputGameJar", gameJars.get(0));
		share.put("fabric-loader:inputGameJars", gameJars);

		String version = arguments.remove(Arguments.GAME_VERSION);

		processArgumentMap(arguments, envType);

		return true;
	}

	private static void processArgumentMap(Arguments argMap, EnvType envType) {
		switch (envType) {
			case CLIENT:
				if (!argMap.containsKey("accessToken")) {
					argMap.put("accessToken", "QuiltMC");
				}


				if (!argMap.containsKey("version")) {
					argMap.put("version", "Unknown");
				}

				String versionType = "";

				if (argMap.containsKey("versionType") && !argMap.get("versionType").equalsIgnoreCase("release")) {
					versionType = argMap.get("versionType") + "/";
				}

				argMap.put("versionType", versionType + "Quilt Loader " + QuiltLoaderImpl.VERSION);

				if (!argMap.containsKey("gameDir")) {
					argMap.put("gameDir", getLaunchDirectory(argMap).toAbsolutePath().normalize().toString());
				}

				break;
			case SERVER:
				argMap.remove("version");
				argMap.remove("gameDir");
				argMap.remove("assetsDir");
				break;
		}
	}

	private static Path getLaunchDirectory(Arguments argMap) {
		return Paths.get(argMap.getOrDefault("gameDir", "."));
	}

	@Override
	public boolean isGameClass(String name) {
		return name.startsWith("net.minecraft.") || name.startsWith("com.b100.");
	}

	@Override
	public void initialize(QuiltLauncher launcher) {
		if (!logJars.isEmpty() && !Boolean.getBoolean(SystemProperties.UNIT_TEST)) {
			for (Path jar : logJars) {
				if (gameJars.contains(jar)) {
					launcher.addToClassPath(jar, ALLOWED_EARLY_CLASS_PREFIXES);
				} else {
					launcher.addToClassPath(jar);
				}
			}
		}

		setupLogHandler(launcher, true);

		transformer.locateEntrypoints(launcher, gameJars);
	}

	private void setupLogHandler(QuiltLauncher launcher, boolean useTargetCl) {
		System.setProperty("log4j2.formatMsgNoLookups", "true"); // lookups are not used by mc and cause issues with older log4j2 versions

		try {
			final String logHandlerClsName;

			if (log4jAvailable) {
				logHandlerClsName = "org.quiltmc.loader.impl.game.minecraft.Log4jLogHandler";
			} else if (slf4jAvailable) {
				logHandlerClsName = "org.quiltmc.loader.impl.game.minecraft.Slf4jLogHandler";
			} else {
				return;
			}

			ClassLoader prevCl = Thread.currentThread().getContextClassLoader();
			Class<?> logHandlerCls;

			if (useTargetCl) {
				Thread.currentThread().setContextClassLoader(launcher.getTargetClassLoader());
				logHandlerCls = launcher.loadIntoTarget(logHandlerClsName);
			} else {
				logHandlerCls = Class.forName(logHandlerClsName);
			}

			Log.init((LogHandler) logHandlerCls.getConstructor().newInstance(), true);
			Thread.currentThread().setContextClassLoader(prevCl);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public Arguments getArguments() {
		return arguments;
	}

	@Override
	public String[] getLaunchArguments(boolean sanitize) {
		if (arguments == null) return new String[0];

		String[] ret = arguments.toArray();
		if (!sanitize) return ret;

		int writeIdx = 0;

		for (int i = 0; i < ret.length; i++) {
			String arg = ret[i];

			if (i + 1 < ret.length
					&& arg.startsWith("--")
					&& SENSITIVE_ARGS.contains(arg.substring(2).toLowerCase(Locale.ENGLISH))) {
				i++; // skip value
			} else {
				ret[writeIdx++] = arg;
			}
		}

		if (writeIdx < ret.length) ret = Arrays.copyOf(ret, writeIdx);

		return ret;
	}

	@Override
	public GameTransformer getEntrypointTransformer() {
		return transformer;
	}

	@Override
	public boolean canOpenGui() {
		if (arguments == null || envType == EnvType.CLIENT) {
			return true;
		}

		List<String> extras = arguments.getExtraArgs();
		return !extras.contains("nogui") && !extras.contains("--nogui");
	}

	@Override
	public boolean hasAwtSupport() {
		// MC always sets -XstartOnFirstThread for LWJGL
		return !LoaderUtil.hasMacOs();
	}

	@Override
	public void unlockClassPath(QuiltLauncher launcher) {
		for (Path gameJar : gameJars) {
			if (logJars.contains(gameJar)) {
				launcher.setAllowedPrefixes(gameJar);
			} else {
				launcher.addToClassPath(gameJar);
			}
		}

		for (Path lib : miscGameLibraries) {
			launcher.addToClassPath(lib);
		}
	}

	@Override
	public void launch(ClassLoader loader) {
		String targetClass = entrypoint;

		Log.debug(LogCategory.GAME_PROVIDER, "Launching using target class '" + targetClass + "'");

		try {
			Class<?> c = loader.loadClass(targetClass);
			Method m = c.getMethod("main", String[].class);
			m.invoke(null, (Object) arguments.toArray());
		} catch (InvocationTargetException e) {
			throw new FormattedException("Better than Adventure has crashed!", e.getCause());
		} catch (ReflectiveOperationException e) {
			throw new FormattedException("Failed to start Better than Adventure", e);
		}
	}
}

package fish.cichlidmc.cichlid_gradle.extension.def;

import fish.cichlidmc.cichlid_gradle.cache.CichlidCache;
import fish.cichlidmc.cichlid_gradle.run.RunConfiguration;
import fish.cichlidmc.cichlid_gradle.run.RunConfigurationImpl;
import fish.cichlidmc.cichlid_gradle.util.Distribution;
import fish.cichlidmc.cichlid_gradle.util.Utils;
import fish.cichlidmc.cichlid_gradle.util.hash.HashAlgorithm;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.dsl.DependencyFactory;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.MessageDigest;

public final class MinecraftDefinitionImpl implements MinecraftDefinition {
	private final String name;

	private final Property<String> version;
	private final Property<Distribution> distribution;
	private final NamedDomainObjectContainer<RunConfigurationImpl> runs;
	private final TransformersImpl transformers;

	private final Provider<ExternalModuleDependency> dependency;

	private DefHash hash;

	public MinecraftDefinitionImpl(String name, Project project) {
		if (name.contains("$")) {
			throw new InvalidUserDataException("Minecraft definition names may not contain '$'");
		}

		this.name = name;

		ObjectFactory objects = project.getObjects();

		this.version = objects.property(String.class);
		this.version.finalizeValueOnRead();

		this.distribution = objects.property(Distribution.class).convention(Distribution.MERGED);
		this.distribution.finalizeValueOnRead();

		this.runs = objects.domainObjectContainer(RunConfigurationImpl.class, RunConfigurationImpl.factory(project));

		this.transformers = TransformersImpl.of(name, project.getConfigurations());

		DependencyFactory depFactory = project.getDependencyFactory();
		this.dependency = project.getProviders().provider(() -> this.createDependency(depFactory));
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public Property<String> getVersion() {
		return this.version;
	}

	@Override
	public Property<Distribution> getDistribution() {
		return this.distribution;
	}

	@Override
	public Provider<ExternalModuleDependency> getDependency() {
		return this.dependency;
	}

	@Override
	public TransformersImpl getTransformers() {
		return this.transformers;
	}

	@Override
	public NamedDomainObjectContainer<? extends RunConfiguration> getRuns() {
		return this.runs;
	}

	public String version() {
		if (this.version.isPresent()) {
			return this.version.get();
		}

		throw new InvalidUserDataException("Minecraft definition '" + this.name + "' does not have a version specified");
	}

	public Distribution dist() {
		return this.distribution.get();
	}

	public DefHash hash() throws IOException {
		if (this.hash != null)
			return this.hash;

		MessageDigest digest = HashAlgorithm.SHA256.digest();

		// include the current format so bumping it causes a refresh
		digest.update(Utils.bytes(CichlidCache.FORMAT));
		digest.update(this.version().getBytes(StandardCharsets.UTF_8));
		digest.update(this.dist().bytes);

		for (File file : this.transformers.collectFiles()) {
			digest.update(file.getPath().getBytes(StandardCharsets.UTF_8));

			if (file.isDirectory())
				continue;

			try (DigestInputStream stream = new DigestInputStream(Files.newInputStream(file.toPath()), digest)) {
				// let the implementation read bytes in whatever way is most optimal
				stream.transferTo(OutputStream.nullOutputStream());
			}
		}

		this.hash = DefHash.of(digest.digest());
		return this.hash;
	}

	private ExternalModuleDependency createDependency(DependencyFactory factory) throws IOException {
		String module = "minecraft-" + this.dist();
		String version = this.version() + '$' + this.name + '$' + this.hash().shortString();
		ExternalModuleDependency dep = factory.create("net.minecraft", module, version);

		// set as changing so gradle only caches it for 24 hours.
		// since the hash is short, collisions aren't out of the question over a long period of time.
		dep.setChanging(true);

		return dep;
	}
}

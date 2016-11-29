package com.cumulocity.maven3.plugin.thirdlicense.jar;

import com.cumulocity.maven3.plugin.thirdlicense.mapper.PropertyMapper;

import java.nio.file.Path;

/**
 * Class is simple DTO object only to transfer data.
 */
public class Jar {

    private final String separator;
    private final String groupId;
    private final String artifactId;
    private final String version;
    private final String copyright;
    private final String license;
    private final String fileName;
    private final String absolutePath;
    private final String relativePath;

    private Jar(String separator, String groupId, String artifactId, String version, String copyright, String license, String fileName, String absolutePath, String relativePath) {
        this.separator = separator;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.copyright = copyright;
        this.license = license;
        this.fileName = fileName;
        this.absolutePath = absolutePath;
        this.relativePath = relativePath;
    }

    public static Jar of(Path jarPath, Path basedir, PropertyMapper propertyMapper) {
        return new Jar(
                Jars.toSeparator(jarPath)
                , Jars.toGroupId(jarPath, propertyMapper)
                , Jars.toArtifactId(jarPath, propertyMapper)
                , Jars.toVersion(jarPath, propertyMapper)
                , Jars.toCopyright(jarPath, propertyMapper)
                , Jars.toLicense(jarPath, propertyMapper)
                , Jars.toFileName(jarPath)
                , Jars.toAbsolutePath(jarPath)
                , Jars.toRelativePath(jarPath, basedir)
        );
    }

    public String getFileName() {
        return fileName;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getVersion() {
        return version;
    }

    public String getCopyright() {
        return copyright;
    }

    public String getLicense() {
        return license;
    }
    
    public boolean isCumulocityJar() {
        return getGroupId() != null && getGroupId().startsWith("com.nsn.cumulocity");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Jar jar = (Jar) o;
        return absolutePath.equals(jar.absolutePath);
    }

    @Override
    public int hashCode() {
        return absolutePath.hashCode();
    }

    @Override
    public String toString() {
        return "Jar{" + relativePath + separator + fileName + "}";
    }
}

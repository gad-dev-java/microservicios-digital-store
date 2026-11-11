package com.portafolio.catalog_service.infrastructure.adapters.out.files;

import com.portafolio.catalog_service.application.ports.out.AssetStorageException;
import com.portafolio.catalog_service.application.ports.out.AssetStoragePort;
import com.portafolio.catalog_service.domain.exceptions.InvalidProductException;
import com.portafolio.catalog_service.domain.models.AssetMetadata;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalAssetStorageAdapter implements AssetStoragePort {
    private static final long MAX_BYTES = 50L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES =
            Set.of("application/pdf", "application/zip", "application/x-zip-compressed", "text/plain");

    private final Path root;

    public LocalAssetStorageAdapter(@Value("${catalog.storage.root}") String directory) {
        root = Path.of(directory).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            throw new AssetStorageException("No se pudo preparar el almacén", exception);
        }
    }

    @Override
    public AssetMetadata store(InputStream source, String originalName, String contentType, long expectedSize) {
        if (expectedSize < 1 || expectedSize > MAX_BYTES) {
            throw new InvalidProductException("El archivo debe medir entre 1 byte y 50 MB");
        }
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new InvalidProductException("Tipo de archivo no permitido");
        }

        String key = UUID.randomUUID().toString();
        Path destination = resolve(key);
        MessageDigest digest = sha256();
        long actualSize = 0;

        try (OutputStream output =
                Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = source.read(buffer)) != -1) {
                actualSize += read;
                if (actualSize > MAX_BYTES) {
                    throw new InvalidProductException("El archivo supera 50 MB");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        } catch (IOException exception) {
            deleteQuietly(destination);
            throw new AssetStorageException("No se pudo guardar el archivo", exception);
        } catch (RuntimeException exception) {
            deleteQuietly(destination);
            throw exception;
        }

        if (actualSize != expectedSize) {
            deleteQuietly(destination);
            throw new InvalidProductException("La carga del archivo quedó incompleta");
        }

        return new AssetMetadata(
                key,
                safeFileName(originalName),
                contentType,
                actualSize,
                HexFormat.of().formatHex(digest.digest()));
    }

    @Override
    public boolean matches(AssetMetadata metadata) {
        Path path = resolve(metadata.key());
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }

        MessageDigest digest = sha256();
        long size = 0;
        try (InputStream source = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = source.read(buffer)) != -1) {
                size += read;
                digest.update(buffer, 0, read);
            }
        } catch (IOException exception) {
            throw new AssetStorageException("No se pudo verificar el archivo", exception);
        }

        return size == metadata.size()
                && HexFormat.of().formatHex(digest.digest()).equals(metadata.sha256());
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException exception) {
            throw new AssetStorageException("No se pudo limpiar el archivo", exception);
        }
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new InvalidProductException("Clave de archivo inválida");
        }
        return path;
    }

    private static String safeFileName(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}\";]", "_")
                .trim();
        if (name.isBlank()) {
            name = "archivo";
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // La operación original ya falló; revisar archivos huérfanos en mantenimiento.
        }
    }
}

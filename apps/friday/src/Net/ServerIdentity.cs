using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using Friday.Core;

namespace Friday.Net;

/// <summary>
/// Creates and persists Friday's self-signed TLS identity. The phone pins the
/// SHA-256 fingerprint shown in the desktop UI during pairing, so a different
/// certificate (a man in the middle) is rejected even on a hostile network.
/// </summary>
public sealed class ServerIdentity
{
    public X509Certificate2 Certificate { get; }
    public string Fingerprint { get; }

    private ServerIdentity(X509Certificate2 certificate)
    {
        Certificate = certificate;
        Fingerprint = Convert.ToHexString(certificate.GetCertHash(HashAlgorithmName.SHA256));
    }

    public static ServerIdentity LoadOrCreate()
    {
        AppPaths.Ensure();
        var password = LoadOrCreatePassword();
        X509Certificate2? certificate = null;

        try
        {
            if (File.Exists(AppPaths.ServerPfx))
            {
                certificate = X509CertificateLoader.LoadPkcs12FromFile(
                    AppPaths.ServerPfx, password,
                    X509KeyStorageFlags.Exportable | X509KeyStorageFlags.UserKeySet);
            }
        }
        catch (Exception ex)
        {
            FLog.Warn("tls", "Existing certificate could not be loaded; creating a new one: " + ex.Message);
            certificate = null;
        }

        if (certificate is null || !certificate.HasPrivateKey)
        {
            certificate = Create(password);
            FLog.Info("tls", "Created a new self-signed TLS certificate for Friday.");
        }

        return new ServerIdentity(certificate);
    }

    private static X509Certificate2 Create(string password)
    {
        using var rsa = RSA.Create(2048);
        var request = new CertificateRequest(
            "CN=Friday Local Agent", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);

        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, false));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(
            X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, false));
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(
            new OidCollection { new("1.3.6.1.5.5.7.3.1") }, false));

        var san = new SubjectAlternativeNameBuilder();
        san.AddDnsName("friday.local");
        san.AddDnsName(Environment.MachineName);
        san.AddIpAddress(IPAddress.Loopback);
        foreach (var address in LocalAddresses())
        {
            try { san.AddIpAddress(address); } catch { }
        }
        request.CertificateExtensions.Add(san.Build());

        using var ephemeral = request.CreateSelfSigned(
            DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(5));

        var pfx = ephemeral.Export(X509ContentType.Pfx, password);
        File.WriteAllBytes(AppPaths.ServerPfx, pfx);
        return X509CertificateLoader.LoadPkcs12(pfx, password,
            X509KeyStorageFlags.Exportable | X509KeyStorageFlags.UserKeySet);
    }

    private static string LoadOrCreatePassword()
    {
        try
        {
            if (File.Exists(AppPaths.ServerSecret))
            {
                var protectedBytes = Convert.FromBase64String(File.ReadAllText(AppPaths.ServerSecret).Trim());
                var raw = ProtectedData.Unprotect(protectedBytes, null, DataProtectionScope.CurrentUser);
                return Encoding.UTF8.GetString(raw);
            }
        }
        catch (Exception ex)
        {
            FLog.Warn("tls", "Could not read the certificate password; rotating it: " + ex.Message);
        }

        var password = PairingStore.Base64Url(RandomNumberGenerator.GetBytes(24));
        var protectedPassword = ProtectedData.Protect(
            Encoding.UTF8.GetBytes(password), null, DataProtectionScope.CurrentUser);
        File.WriteAllText(AppPaths.ServerSecret, Convert.ToBase64String(protectedPassword));
        return password;
    }

    public static IPAddress[] LocalAddresses()
    {
        try
        {
            return NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == OperationalStatus.Up &&
                            n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
                .SelectMany(n => n.GetIPProperties().UnicastAddresses)
                .Select(a => a.Address)
                .Where(a => a.AddressFamily == AddressFamily.InterNetwork &&
                            !IPAddress.IsLoopback(a) &&
                            !a.ToString().StartsWith("169.254."))
                .Distinct()
                .ToArray();
        }
        catch
        {
            return Array.Empty<IPAddress>();
        }
    }
}

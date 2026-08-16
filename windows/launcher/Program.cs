using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Threading.Tasks;

namespace WolProxyLauncher;

internal static class Program
{
    private const string Version = "1.0.2";
    private const string PayloadResourceName = "wol-proxy-payload.zip";

    private static async Task<int> Main(string[] args)
    {
        Console.OutputEncoding = Encoding.UTF8;

        try
        {
            byte[] payload = ReadPayload();
            string runtimeRoot = PrepareRuntime(payload);
            string applicationRoot = Path.Combine(runtimeRoot, "WOL-Proxy");
            string serviceExecutable = Path.Combine(applicationRoot, "WOL-Proxy.exe");
            string jarPath = Path.Combine(applicationRoot, "app", "wol-proxy.jar");

            if (!File.Exists(serviceExecutable) || !File.Exists(jarPath))
            {
                throw new InvalidOperationException("内置代理运行文件不完整");
            }

            var startInfo = new ProcessStartInfo
            {
                FileName = serviceExecutable,
                WorkingDirectory = applicationRoot,
                UseShellExecute = false,
                CreateNoWindow = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8
            };
            foreach (string argument in args)
            {
                startInfo.ArgumentList.Add(argument);
            }

            using Process child = Process.Start(startInfo)
                ?? throw new InvalidOperationException("无法启动代理服务");

            Console.CancelKeyPress += (_, eventArgs) =>
            {
                eventArgs.Cancel = true;
                try
                {
                    if (!child.HasExited)
                    {
                        child.Kill(entireProcessTree: true);
                    }
                }
                catch (InvalidOperationException)
                {
                }
            };

            Task outputTask = ForwardOutputAsync(child.StandardOutput, Console.Out);
            Task errorTask = ForwardOutputAsync(child.StandardError, Console.Error);
            Task processTask = child.WaitForExitAsync();
            await Task.WhenAll(outputTask, errorTask, processTask);
            return child.ExitCode;
        }
        catch (Exception)
        {
            Console.Error.WriteLine($"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}][启动失败]WOL 代理服务启动失败，请检查 EXE 文件是否完整。");
            return 1;
        }
    }

    private static async Task ForwardOutputAsync(StreamReader reader, TextWriter writer)
    {
        while (await reader.ReadLineAsync() is { } line)
        {
            await writer.WriteLineAsync(line);
        }
    }

    private static byte[] ReadPayload()
    {
        Assembly assembly = Assembly.GetExecutingAssembly();
        using Stream payloadStream = assembly.GetManifestResourceStream(PayloadResourceName)
            ?? throw new InvalidOperationException("EXE 内没有找到代理运行文件");
        using var buffer = new MemoryStream();
        payloadStream.CopyTo(buffer);
        return buffer.ToArray();
    }

    private static string PrepareRuntime(byte[] payload)
    {
        string payloadHash = Convert.ToHexString(SHA256.HashData(payload)).ToLowerInvariant()[..16];
        string runtimeRoot = Path.Combine(Path.GetTempPath(), "WOL-Proxy", Version + "-" + payloadHash);
        string markerPath = Path.Combine(runtimeRoot, ".ready");

        if (File.Exists(markerPath) && File.Exists(Path.Combine(runtimeRoot, "WOL-Proxy", "WOL-Proxy.exe")))
        {
            return runtimeRoot;
        }

        if (Directory.Exists(runtimeRoot))
        {
            Directory.Delete(runtimeRoot, recursive: true);
        }

        Directory.CreateDirectory(runtimeRoot);
        using var payloadStream = new MemoryStream(payload, writable: false);
        ZipFile.ExtractToDirectory(payloadStream, runtimeRoot);
        File.WriteAllText(markerPath, "WOL 代理运行文件已准备", Encoding.UTF8);
        return runtimeRoot;
    }
}

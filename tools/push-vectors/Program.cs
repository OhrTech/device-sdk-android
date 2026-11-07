// Fixed keys, so the vectors are the same each run apart from HPKE's fresh ephemeral key.
using System.Reflection;
using System.Text.Json;
using OhrTech.Management.Push;
using Org.BouncyCastle.Crypto.Parameters;

static byte[] Fill(byte b, int n) => Enumerable.Repeat(b, n).ToArray();
static string Hex(byte[] b) => Convert.ToHexString(b).ToLowerInvariant();

var hpke = typeof(PushSealer).Assembly.GetType("OhrTech.Management.Push.Hpke")!;
byte[] XWingPublic(byte[] seed) =>
    (byte[]) hpke.GetMethod("XWingPublic", BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic)!.Invoke(null, [seed])!;

using var sender = PushSenderKey.Import(Fill(0x11, 32), Fill(0x22, 32));
var expires = DateTimeOffset.FromUnixTimeSeconds(1_800_000_000);
var data = "{\"order\":42}"u8.ToArray();
const string token = "psh_vector000000000000000001";

var x25519Private = Fill(0x33, 32);
var x25519Public = new X25519PrivateKeyParameters(x25519Private, 0).GeneratePublicKey().GetEncoded();
var xwingSeed = Fill(0x44, 32);
var xwingPublic = XWingPublic(xwingSeed);

var vectors = new
{
    token,
    senderEd25519 = Hex(sender.PublicKeys.Ed25519),
    senderMlDsa65 = Hex(sender.PublicKeys.MlDsa65!),
    id = "order-42",
    exp = expires.ToUnixTimeSeconds(),
    data = Hex(data),
    x25519 = new
    {
        recipientPrivate = Hex(x25519Private),
        recipientPublic = Hex(x25519Public),
        envelope = Hex(PushSealer.Seal(token, PushSuite.X25519, x25519Public, sender, "order-42", expires, data)),
    },
    xwingMlDsa65 = new
    {
        recipientSeed = Hex(xwingSeed),
        recipientPublic = Hex(xwingPublic),
        envelope = Hex(PushSealer.Seal(token, PushSuite.XWingMlDsa65, xwingPublic, sender, "order-42", expires, data)),
    },
};
File.WriteAllText(args[0], JsonSerializer.Serialize(vectors, new JsonSerializerOptions { WriteIndented = true }) + "\n");
Console.WriteLine("wrote " + args[0]);

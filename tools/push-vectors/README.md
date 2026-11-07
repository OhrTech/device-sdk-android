# push-vectors

Makes `device-sdk/src/test/resources/push-vectors.json`, the library's push test vectors, with the
OhrTech .NET Management SDK's sealer (a checkout of `management-sdk-dotnet` next to this repo, or
`-p:ManagementSdk=<path>`):

```
dotnet run -- ../../device-sdk/src/test/resources/push-vectors.json
```

The keys are fixed; each run seals afresh (new HPKE ephemeral keys), so the envelopes change.

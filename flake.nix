{
  description = "salmon";

  inputs = {
    # Latest stable nixpkgs
    nixpkgs.url = "https://flakehub.com/f/NixOS/nixpkgs/0";
  };

  outputs =
    {
      self,
      nixpkgs,
      ...
    }@inputs:
    let
      systems = [
        "aarch64-darwin"
        "aarch64-linux"
        "x86_64-linux"
      ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
    in
    {
      devShells = forAllSystems (
        system:
        with import nixpkgs {
          inherit system;
          config.allowUnfree = true;
        }; {
          default = mkShell {
            buildInputs = [
              clj-kondo
              clojure
              git
              jdk
              just
              nixfmt
              nodejs
              packer
              python3Packages.cfn-lint
              rlwrap # Used by clj
              time
            ];
          };
        }
      );
    };
}

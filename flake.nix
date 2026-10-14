{
  description = "salmon";

  inputs = {
    # Latest stable nixpkgs
    nixpkgs.url = "https://flakehub.com/f/NixOS/nixpkgs/0";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
      ...
    }@inputs:
    flake-utils.lib.eachDefaultSystem (
      system:
      with import nixpkgs {
        inherit system;
        config.allowUnfree = true;
      }; {
        devShells.default = mkShell {
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
}

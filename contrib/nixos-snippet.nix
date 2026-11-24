# Snippet for NixOS system.activationScripts (already installed in ~/Documents/nixos/configuration.nix):
#
# install -D -m644 -o $USR -g $GRP ${./files/daetron/rules.d/redphone.rule} $HOME_DIR/.config/daetron/rules.d/redphone.rule
# install -D -m644 -o $USR -g $GRP ${./files/daetron/rules.d/sxs-wifi.rule} $HOME_DIR/.config/daetron/rules.d/sxs-wifi.rule
# install -D -m644 -o $USR -g $GRP ${./files/daetron/rules.d/sxs-cd.rule} $HOME_DIR/.config/daetron/rules.d/sxs-cd.rule
# install -D -m644 -o $USR -g $GRP ${./files/systemd/user/daetron.service} $HOME_DIR/.config/systemd/user/daetron.service
# install -D -m644 -o $USR -g $GRP ${./files/systemd/user/redphone-daemon.service} $HOME_DIR/.config/systemd/user/redphone-daemon.service
# mkdir -p $HOME_DIR/.config/systemd/user/default.target.wants
# ln -sfn $HOME_DIR/.config/systemd/user/daetron.service $HOME_DIR/.config/systemd/user/default.target.wants/daetron.service
# ln -sfn $HOME_DIR/.config/systemd/user/redphone-daemon.service $HOME_DIR/.config/systemd/user/default.target.wants/redphone-daemon.service
# chown -R $USR:$GRP $HOME_DIR/.config/daetron $HOME_DIR/.config/systemd/user
{}

%define name linux-device-manager
%define version __VERSION__
%define release 1

# Keep man pages uncompressed so the rpm payload stays byte-identical
# to the deb/arch payloads (rpmbuild gzips them otherwise).
%global __brp_compress %{nil}

Name:           %{name}
Version:        %{version}
Release:        %{release}%{?dist}
Summary:        View and manage hardware devices
License:        GPL-3.0-or-later
URL:            https://github.com/getldm/linux-device-manager
AutoReqProv:    no
Requires:       gtk4 >= 4.14.5
Requires:       polkit
Requires:       systemd-udev
Recommends:     pciutils
Recommends:     usbutils
Recommends:     kmod
Recommends:     systemd

%description
Linux Device Manager is a GTK4 application that enumerates devices
from sysfs/udev, displays them in a category tree with detail tabs
(general, advanced, driver, logs), and allows enabling/disabling
devices through a privileged helper governed by polkit.
Ships a bundled trimmed JRE; only the GTK4 runtime and hardware
tooling are required from the system.

%prep
# nothing to compile — staged tree provided via %{stage}

%build
# no build step

%install
mkdir -p %{buildroot}
cp -a %{stage}/. %{buildroot}/
# hicolor/index.theme is owned by hicolor-icon-theme (same conflict as deb);
# the installing system's index declares the standard size dirs.
rm -f %{buildroot}/usr/share/icons/hicolor/index.theme

%files
%defattr(-,root,root,-)
/opt/linux-device-manager/lib/*.jar
/opt/linux-device-manager/runtime/*
/usr/bin/linux-device-manager
/usr/libexec/ldm-helper
/usr/share/polkit-1/actions/org.ldm.policy
/usr/share/applications/linux-device-manager.desktop
/usr/share/metainfo/org.ldm.LinuxDeviceManager.metainfo.xml
/usr/share/appdata/linux-device-manager.appdata.xml
/usr/share/man/man1/linux-device-manager.1
/usr/share/icons/hicolor/*/apps/linux-device-manager.svg
/usr/share/icons/hicolor/*x*/apps/linux-device-manager.png
%doc /usr/share/doc/linux-device-manager/copyright
%license /usr/share/licenses/linux-device-manager/copyright

%post
chmod 0755 /usr/libexec/ldm-helper || :
update-desktop-database -q || :
gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor || :

%postun
update-desktop-database -q || :
gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor || :

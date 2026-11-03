import 'package:flutter/material.dart';

/// Owns one preview while keeping the chat subtree and reading position alive.
class SurfaceWorkspace extends StatefulWidget {
  const SurfaceWorkspace({
    super.key,
    required this.identity,
    required this.child,
  });
  final String identity;
  final Widget child;
  @override
  State<SurfaceWorkspace> createState() => _SurfaceWorkspaceState();
}

class SurfaceWorkspaceScope extends InheritedWidget {
  const SurfaceWorkspaceScope({
    super.key,
    required super.child,
    required this.selected,
    required this.open,
  });
  final String? selected;
  final Future<void> Function(
    String,
    Widget Function(VoidCallback),
    Future<bool> Function(),
  )
  open;
  static SurfaceWorkspaceScope? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<SurfaceWorkspaceScope>();
  @override
  bool updateShouldNotify(SurfaceWorkspaceScope old) =>
      old.selected != selected;
}

class _SurfaceWorkspaceState extends State<SurfaceWorkspace> {
  String? selected;
  Widget? preview;
  Future<bool> Function()? save;
  bool busy = false;
  int revision = 0;
  @override
  void didUpdateWidget(covariant SurfaceWorkspace old) {
    super.didUpdateWidget(old);
    if (old.identity != widget.identity) {
      revision++;
      selected = null;
      preview = null;
      save = null;
    }
  }

  Future<void> close() async {
    if (busy) return;
    busy = true;
    final requested = revision;
    try {
      if (await save?.call() == false || !mounted || requested != revision)
        return;
      setState(() {
        selected = null;
        preview = null;
        save = null;
      });
    } finally {
      busy = false;
    }
  }

  Future<void> open(
    String id,
    Widget Function(VoidCallback) builder,
    Future<bool> Function() persist,
  ) async {
    if (busy || MediaQuery.sizeOf(context).width < 1000) return;
    final requested = revision;
    if (selected != null) await close();
    if (!mounted || selected != null || requested != revision) return;
    setState(() {
      selected = id;
      save = persist;
      preview = builder(() => close());
    });
  }

  @override
  Widget build(BuildContext context) => SurfaceWorkspaceScope(
    selected: selected,
    open: open,
    child: LayoutBuilder(
      builder: (context, size) => Row(
        children: [
          Expanded(
            child: Column(
              children: [
                if (preview != null && size.maxWidth < 1000)
                  TextButton(onPressed: close, child: const Text('保存并返回聊天内界面')),
                Expanded(child: widget.child),
              ],
            ),
          ),
          if (preview != null) ...[
            if (size.maxWidth >= 1000) const VerticalDivider(width: 1),
            Offstage(
              offstage: size.maxWidth < 1000,
              child: SizedBox(
                width: (size.maxWidth * .42).clamp(400, 560),
                child: Column(
                  children: [
                    Align(
                      alignment: Alignment.centerRight,
                      child: IconButton(
                        tooltip: '保存并关闭预览',
                        onPressed: close,
                        icon: const Icon(Icons.close),
                      ),
                    ),
                    Expanded(
                      child: SingleChildScrollView(
                        padding: const EdgeInsets.all(16),
                        child: preview!,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ],
      ),
    ),
  );
}

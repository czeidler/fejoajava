<?php

include_once './XMLProtocol.php';


class SyncPullStanzaHandler extends InStanzaHandler {
	private $inStreamReader;

	public function __construct($inStreamReader) {
		InStanzaHandler::__construct("sync_pull");
		$this->inStreamReader = $inStreamReader;
	}

	public function getType() {
		return $this->type;
	}

	public function handleStanza($xml) {
		$branch = $xml->getAttribute("branch");
		$serverUser = $xml->getAttribute("serverUser");
		$remoteTip = $xml->getAttribute("base");
		if ($branch === null || $serverUser === null || $remoteTip === null)
			return false;

		$branchAccessToken = $serverUser.":".$branch;
		if (!Session::get()->isAccountUser() && !Session::get()->hasBranchAccess($branchAccessToken))
			return false;
		$database = Session::get()->getDatabase($serverUser);
		if ($database === null)
			return false;

		if (isSHA1Hex($remoteTip))
			$remoteTip = sha1_bin($remoteTip);

		$packManager = new PackManager($database);
		$pack = "";
		try {
			$localTip = $database->getTip($branch);
			$pack = $packManager->exportPack($branch, $remoteTip, $localTip, -1);
		} catch (Exception $e) {
			$localTip = "";
		}

		// produce output
		$outStream = new ProtocolOutStream();
		$outStream->pushStanza(new IqOutStanza(IqType::$kResult));

		$stanza = new OutStanza("sync_pull");
		$stanza->addAttribute("branch", $branch);
		$localTipHex = "";
		$remoteTipHex = "";
		if (strlen($remoteTip) == 20)
			$remoteTipHex = sha1_hex($remoteTip);
		$stanza->addAttribute("base", $remoteTipHex);
		if (strlen($localTip) == 20)
			$localTipHex = sha1_hex($localTip);
		$stanza->addAttribute("tip", $localTipHex);
		$outStream->pushChildStanza($stanza);
		
		$packStanza = new OutStanza("pack");
		$packStanza->setText(base64_encode($pack));
		$outStream->pushChildStanza($packStanza);
		
		$this->inStreamReader->appendResponse($outStream->flush());
		return true;
	}
}

class SyncPushPackHandler extends InStanzaHandler {
	private $pack;

	public function __construct() {
		InStanzaHandler::__construct("pack");
	}
	
	public function handleStanza($xml) {
		$this->pack = url_decode($xml->readString());
		return true;
	}
	
	public function getPack() {
		return $this->pack;
	}
}

class SyncPushStanzaHandler extends InStanzaHandler {
	private $inStreamReader;
	private $packHandler;
	
	private $branch;
	private $serverUser;
	private $startCommit;
	private $lastCommit;

	public function __construct($inStreamReader) {
		InStanzaHandler::__construct("sync_push");
		$this->inStreamReader = $inStreamReader;
	}

	public function handleStanza($xml) {
		$this->branch = $xml->getAttribute("branch");
		$this->startCommit = $xml->getAttribute("start_commit");
		$this->lastCommit = $xml->getAttribute("last_commit");
		$this->serverUser = $xml->getAttribute("serverUser");
		
		if ($this->branch === null || $this->startCommit === null || $this->lastCommit === null
			|| $this->serverUser === null)
			return false;

		$this->packHandler = new SyncPushPackHandler();
		$this->addChild($this->packHandler);
		return true;
	}
	
	public function finished() {
		$branchAccessToken = $this->serverUser.":".$this->branch;
		if (!Session::get()->isAccountUser() && !Session::get()->hasBranchAccess($branchAccessToken)) {
			$this->inStreamReader->appendResponse(IqErrorOutStanza::makeErrorMessage("Push: access to branch denied."));
			return;
		}
		$database = Session::get()->getDatabase($this->serverUser);
		if ($database === null) {
			$this->inStreamReader->appendResponse(IqErrorOutStanza::makeErrorMessage("Push: no such branch."));
			return;
		}

		$pack = $this->packHandler->getPack();

		$packManager = new PackManager($database);
		if (!$packManager->importPack($this->branch, $pack, $this->startCommit, $this->lastCommit)) {
			$this->inStreamReader->appendResponse(IqErrorOutStanza::makeErrorMessage("Push: unable to import pack."));
			return;
		}

		$localTip = sha1_hex($database->getTip($this->branch));

		// if somebody else sent us the branch update the tip in the mailbox so that the client
		// finds out about the new update
		if (!Session::get()->isAccountUser()) {
			$mailbox = Session::get()->getMainMailbox($this->serverUser);
			if ($mailbox != null) {
				if ($mailbox->updateChannelTip($this->branch, $localTip))
					$mailbox->commit();
			}
		}

		// produce output
		$outStream = new ProtocolOutStream();
		$outStream->pushStanza(new IqOutStanza(IqType::$kResult));

		$stanza = new OutStanza("sync_push");
		$stanza->addAttribute("branch", $this->branch);
		$stanza->addAttribute("tip", $localTip);
		$outStream->pushChildStanza($stanza);

		$this->inStreamReader->appendResponse($outStream->flush());
	}
}

?>
